package com.jobit.jd;

import com.jobit.llm.EmbeddingClient;
import com.jobit.llm.LlmGuard;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * JD 파싱 흐름 (스펙 §4.1).
 *
 * <pre>
 * 1. 본문 정규화 → 해시
 * 2. content_hash로 조회 → 있으면 그대로 재사용 (LLM 호출 없음)
 * 3. 없으면 파싱 → job_posting + requirement 저장
 * </pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JdParsingService {

	private final JobPostingRepository jobPostingRepository;

	private final RequirementRepository requirementRepository;

	private final RequirementEmbeddingRepository embeddingRepository;

	private final JdParser jdParser;

	private final EmbeddingClient embeddingClient;

	private final LlmGuard llmGuard;

	/**
	 * 캐시가 있으면 재사용하고, 없으면 파싱해 저장한다.
	 *
	 * <p>같은 공고를 두 사용자가 동시에 넣으면 둘 다 캐시 미스로 판단해 나란히 파싱할 수 있다.
	 * {@code content_hash} 유니크 제약이 최종 방어선이며, 충돌하면 이미 저장된 쪽을 쓴다.
	 * LLM 호출 한 번이 낭비되지만 락을 잡는 것보다 낫다 — 흔한 상황이 아니다.
	 */
	@Transactional
	public Outcome parseOrGetCached(String rawText, String sourceUrl, String ownerKey) {
		String contentHash = JdTextNormalizer.contentHash(rawText);

		var cached = jobPostingRepository.findByContentHash(contentHash);
		if (cached.isPresent()) {
			log.debug("JD cache hit: {}", contentHash);
			return new Outcome(cached.get(), true);
		}

		// 캐시를 지나온 뒤에야 소비한다 — 캐시 적중은 돈이 나가지 않으므로 한도도 쓰지 않는다.
		llmGuard.checkAndConsume(ownerKey);

		JdParser.ParsedJd parsed = jdParser.parse(JdTextNormalizer.normalize(rawText));

		try {
			return new Outcome(save(contentHash, rawText, sourceUrl, parsed), false);
		}
		catch (DataIntegrityViolationException ex) {
			// 동시 요청이 먼저 저장했다. 그쪽 결과를 쓴다.
			log.debug("JD cache race lost, reusing existing: {}", contentHash);
			return new Outcome(
					jobPostingRepository.findByContentHash(contentHash).orElseThrow(() -> ex), true);
		}
	}

	/**
	 * @param cached LLM을 부르지 않고 재사용했는지. 레이트 리밋 소비 여부를 가르는 값이라
	 *               호출자에게 알려야 한다 (docs/api.md).
	 */
	public record Outcome(JobPosting jobPosting, boolean cached) {
	}

	private JobPosting save(String contentHash, String rawText, String sourceUrl,
			JdParser.ParsedJd parsed) {
		JobPosting posting = jobPostingRepository.save(new JobPosting(contentHash, rawText,
				sourceUrl, parsed.company(), parsed.title(), parsed.parsedJson()));

		List<Requirement> requirements = new ArrayList<>();
		List<JdParser.ParsedRequirement> source = parsed.requirements();
		for (int i = 0; i < source.size(); i++) {
			JdParser.ParsedRequirement r = source.get(i);
			requirements.add(new Requirement(posting, r.text(), r.kind(),
					r.keywords().toArray(String[]::new), i));
		}
		// flush 가 필요하다 — 아래 임베딩 저장은 JdbcClient UPDATE 라 JPA 영속성 컨텍스트를
		// 보지 않는다 (ResumeService 와 같은 함정: INSERT 전이면 조용히 0건 업데이트다).
		List<Requirement> saved = requirementRepository.saveAllAndFlush(requirements);
		embedRequirements(saved);

		log.info("JD parsed: hash={} requirements={}", contentHash, requirements.size());
		return posting;
	}

	/**
	 * 요구사항 임베딩 — 질문 은행(스펙 §5)의 검색 축.
	 *
	 * <p><b>실패해도 파싱을 죽이지 않는다.</b> 사용자가 원한 것은 파싱 결과이고 임베딩은 은행
	 * enrichment 다 — 여기서 던지면 방금 성공한 LLM 파싱까지 롤백된다. null 로 남은 행은
	 * 검색이 {@code embedding is not null} 로 거르고, 질문 은행에서 빠질 뿐이다.
	 *
	 * <p><b>개수가 어긋나도 같은 이유로 버린다.</b> 이력서 쪽은 어긋난 저장이 갭 분석을
	 * 오염시키므로 업로드 전체를 실패시키지만, 여기는 부가 자산이라 결이 다르다.
	 */
	private void embedRequirements(List<Requirement> requirements) {
		try {
			List<String> texts = new ArrayList<>(requirements.size());
			for (Requirement requirement : requirements) {
				texts.add(requirement.getText());
			}

			List<float[]> vectors = embeddingClient.embedAll(texts);
			if (vectors.size() != requirements.size()) {
				log.warn("요구사항 임베딩 개수 불일치 ({} != {}) — 질문 은행에서 이 공고를 뺀다",
						vectors.size(), requirements.size());
				return;
			}
			for (int i = 0; i < requirements.size(); i++) {
				embeddingRepository.updateEmbedding(requirements.get(i).getId(), vectors.get(i));
			}
		}
		catch (RuntimeException ex) {
			log.warn("요구사항 임베딩 실패 — 파싱 결과는 유지하고 질문 은행에서만 뺀다: {}", ex.toString());
		}
	}

	@Transactional(readOnly = true)
	public List<Requirement> requirementsOf(JobPosting posting) {
		return requirementRepository.findByJobPostingIdOrderBySortOrder(posting.getId());
	}
}
