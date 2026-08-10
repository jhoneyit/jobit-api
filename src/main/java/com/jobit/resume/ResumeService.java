package com.jobit.resume;

import com.jobit.common.NotFoundException;
import com.jobit.common.TextCipher;
import com.jobit.llm.EmbeddingClient;
import com.jobit.llm.LlmException;
import com.jobit.llm.LlmGuard;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 이력서 업로드 흐름 (스펙 §3.3, §4.3 1단계 준비).
 *
 * <pre>
 * 1. 한도 확인          — 돈이 나가기 직전
 * 2. 문장 분해 (LLM)    — 느리다. 수십 초
 * 3. 임베딩 (Ollama)    — 생성보다 훨씬 가볍다
 * 4. 저장               — 원문 암호화 + 문장 + 벡터를 한 트랜잭션에
 * </pre>
 *
 * <p><b>LLM 호출을 트랜잭션 밖에 둔다.</b> {@code JdParsingService} 는 {@code @Transactional}
 * 안에서 파싱하지만 여기서는 따라하지 않았다 — 이력서 분해는 수십 초가 걸리고, 그동안 DB 커넥션을
 * 붙들고 있으면 동시 업로드 몇 건만으로 커넥션 풀이 마른다. 그러면 <b>업로드와 무관한 기능까지</b>
 * 함께 멈춘다. 그래서 느린 작업을 모두 끝낸 뒤 {@link TransactionTemplate} 로 짧은 쓰기
 * 트랜잭션만 연다.
 *
 * <p><b>임베딩을 저장 전에 끝내는 것도 같은 이유의 연장이다.</b> 벡터는 문장 텍스트만 있으면
 * 계산되므로 저장을 기다릴 필요가 없고, 덕분에 "문장은 저장됐는데 벡터가 없는" 반쪽 상태가
 * 아예 생기지 않는다. 그런 이력서가 하나라도 생기면 이후 갭 분석 코드가 매번 그 경우를 방어해야
 * 한다.
 *
 * <p><b>이력서 원문은 이 클래스 밖으로 나가지 않는다</b> (스펙 §6). 저장할 때 암호화하고,
 * 조회 API 는 문장 목록만 돌려준다 — 복호화 경로는 리라이트(§4.4)가 들어올 때 필요해진다.
 */
@Service
@Slf4j
public class ResumeService {

	private final ResumeRepository resumeRepository;

	private final ResumeBulletRepository bulletRepository;

	private final ResumeBulletEmbeddingRepository embeddingRepository;

	private final ResumeParser resumeParser;

	private final EmbeddingClient embeddingClient;

	private final TextCipher textCipher;

	private final LlmGuard llmGuard;

	private final TransactionTemplate transactionTemplate;

	private final Clock clock;

	private final int ttlDays;

	public ResumeService(ResumeRepository resumeRepository, ResumeBulletRepository bulletRepository,
			ResumeBulletEmbeddingRepository embeddingRepository, ResumeParser resumeParser,
			EmbeddingClient embeddingClient, TextCipher textCipher, LlmGuard llmGuard,
			TransactionTemplate transactionTemplate, Clock clock,
			@Value("${jobit.resume.ttl-days:90}") int ttlDays) {

		this.resumeRepository = resumeRepository;
		this.bulletRepository = bulletRepository;
		this.embeddingRepository = embeddingRepository;
		this.resumeParser = resumeParser;
		this.embeddingClient = embeddingClient;
		this.textCipher = textCipher;
		this.llmGuard = llmGuard;
		this.transactionTemplate = transactionTemplate;
		this.clock = clock;
		this.ttlDays = ttlDays;
	}

	/**
	 * 이력서를 분해해 저장한다.
	 *
	 * <p><b>캐시가 없다.</b> JD 는 {@code content_hash} 로 전역 재사용하지만 이력서는 개인 자산이라
	 * 남과 공유할 수 없고, 같은 사람이 같은 이력서를 다시 올리는 것은 대개 <b>내용을 고쳤기
	 * 때문</b>이라 재사용이 오히려 틀린 동작이다.
	 */
	public Stored upload(String ownerKey, String rawText) {
		// 키가 없으면 여기서 끊는다. LLM 을 먼저 부르고 나서 저장 직전에 실패하면 돈만 나간다.
		if (!textCipher.enabled()) {
			throw new TextCipher.NotConfiguredException();
		}

		// 캐시가 없으므로 조건 없이 소비한다. 임베딩 호출은 따로 세지 않는다 —
		// 단가가 분해 호출의 1/1000 수준이라 별도 방어를 둘 만한 지출이 아니다.
		llmGuard.checkAndConsume(ownerKey);

		ResumeParser.ParsedResume parsed = resumeParser.parse(rawText);
		List<ResumeParser.ParsedBullet> bullets = parsed.bullets();

		List<float[]> vectors = embed(bullets);

		return transactionTemplate.execute(status -> persist(ownerKey, rawText, bullets, vectors));
	}

	private List<float[]> embed(List<ResumeParser.ParsedBullet> bullets) {
		List<String> texts = new ArrayList<>(bullets.size());
		for (ResumeParser.ParsedBullet bullet : bullets) {
			texts.add(bullet.text());
		}

		List<float[]> vectors = embeddingClient.embedAll(texts);

		// **개수가 어긋나면 여기서 끊는다.** 그대로 진행하면 인덱스가 밀려 문장과 벡터가
		// 어긋난 채 저장되고, 갭 분석이 엉뚱한 문장을 근거로 집는다 — 결과가 그럴듯해서
		// 사후에 알아채기 가장 어려운 종류의 버그다.
		if (vectors.size() != bullets.size()) {
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
					"이력서를 분석하지 못했습니다. 잠시 후 다시 시도해 주세요.");
		}
		return vectors;
	}

	private Stored persist(String ownerKey, String rawText,
			List<ResumeParser.ParsedBullet> parsedBullets, List<float[]> vectors) {

		OffsetDateTime expiresAt = OffsetDateTime.now(clock).plusDays(ttlDays);
		Resume resume = resumeRepository
			.save(new Resume(ownerKey, textCipher.encrypt(rawText), null, expiresAt));

		List<ResumeBullet> entities = new ArrayList<>(parsedBullets.size());
		for (int i = 0; i < parsedBullets.size(); i++) {
			ResumeParser.ParsedBullet parsed = parsedBullets.get(i);
			entities.add(new ResumeBullet(resume, parsed.company(), parsed.period(), parsed.text(),
					i));
		}

		// **flush 가 필요하다.** 아래 임베딩 저장은 JdbcClient 로 나가는 UPDATE 라 JPA
		// 영속성 컨텍스트를 보지 않는다. INSERT 가 아직 안 나갔으면 갱신할 행이 없어
		// 조용히 0건 업데이트로 끝난다 — 예외도 나지 않는다.
		List<ResumeBullet> saved = bulletRepository.saveAllAndFlush(entities);

		for (int i = 0; i < saved.size(); i++) {
			embeddingRepository.updateEmbedding(saved.get(i).getId(), vectors.get(i));
		}

		log.info("이력서 저장: resume={} 문장={}개 만료={}", resume.getId(), saved.size(), expiresAt);
		return new Stored(resume, saved.size());
	}

	/**
	 * 내 이력서 하나. <b>남의 것이면 없는 것과 같이 취급한다</b> (docs/api.md 소유자 검사) —
	 * 조회 조건에 {@code ownerKey} 가 들어가므로 호출부가 소유자를 다시 비교할 필요가 없다.
	 */
	@Transactional(readOnly = true)
	public Detail getOwned(UUID resumeId, String ownerKey) {
		Resume resume = resumeRepository.findByIdAndOwnerKey(resumeId, ownerKey)
			.orElseThrow(() -> new NotFoundException("resume not found: " + resumeId));

		return new Detail(resume, bulletRepository.findByResumeIdOrderBySortOrder(resumeId),
				embeddingRepository.countWithEmbedding(resumeId));
	}

	@Transactional(readOnly = true)
	public List<Resume> listOwned(String ownerKey) {
		return resumeRepository.findByOwnerKeyOrderByCreatedAtDesc(ownerKey);
	}

	/**
	 * 이력서를 지운다. 문장과 벡터는 {@code on delete cascade} 로 함께 사라진다.
	 *
	 * <p>JD 제출 이력과 달리 <b>남기는 것이 없다</b> — 공고는 전역 캐시라 지우면 남의 캐시 적중이
	 * 깨지지만, 이력서는 처음부터 끝까지 이 사람의 것이다.
	 */
	@Transactional
	public void delete(UUID resumeId, String ownerKey) {
		Resume resume = resumeRepository.findByIdAndOwnerKey(resumeId, ownerKey)
			.orElseThrow(() -> new NotFoundException("resume not found: " + resumeId));
		resumeRepository.delete(resume);
		log.info("이력서 삭제: resume={}", resumeId);
	}

	/**
	 * 익명으로 올린 이력서를 계정으로 승계한다 (스펙 §3.6).
	 *
	 * <p>제출 이력·면접 기록과 달리 <b>충돌 처리가 없다.</b> 같은 사람이 이력서를 여러 개 갖는 것이
	 * 정상이라 유니크 제약 자체가 없기 때문이다.
	 */
	@Transactional
	public int claim(String fromAnonymousKey, String toUserKey) {
		int moved = resumeRepository.transferOwnership(fromAnonymousKey, toUserKey);
		if (moved > 0) {
			log.info("이력서 {}건을 계정으로 승계했습니다", moved);
		}
		return moved;
	}

	/**
	 * @param bulletCount 저장된 문장 수. 임베딩은 같은 트랜잭션에서 전부 채워지므로
	 *                    "문장 수 = 벡터 수"가 항상 성립한다
	 */
	public record Stored(Resume resume, int bulletCount) {
	}

	/**
	 * @param embeddedCount 벡터가 채워진 문장 수. 정상이면 {@code bullets.size()} 와 같다 —
	 *                      다르면 스케줄 정리나 마이그레이션이 뭔가를 건드렸다는 신호다
	 */
	public record Detail(Resume resume, List<ResumeBullet> bullets, int embeddedCount) {
	}
}
