package com.jobit.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.jobit.common.TextCipher;
import com.jobit.llm.EmbeddingClient;
import com.jobit.llm.LlmException;
import com.jobit.llm.LlmGuard;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 이력서 업로드 흐름의 규칙 (스펙 §3.3).
 *
 * <p>여기서 고정하는 것은 대부분 <b>순서</b>다. 각 단계가 개별적으로 동작하는 것만으로는
 * 부족하고, 잘못된 순서로 엮이면 돈이 새거나 데이터가 어긋난다.
 */
class ResumeServiceTest {

	private static final String KEY = Base64.getEncoder()
		.encodeToString("0123456789abcdef0123456789abcdef".getBytes());

	private static final String OWNER = "user:u-1";

	private static final String RAW = "결제 서버를 개발하고 CI/CD 를 구축했습니다.";

	private ResumeRepository resumeRepository;

	private ResumeBulletRepository bulletRepository;

	private ResumeBulletEmbeddingRepository embeddingRepository;

	private ResumeParser resumeParser;

	private EmbeddingClient embeddingClient;

	private LlmGuard llmGuard;

	private ResumeService service;

	@BeforeEach
	void setUp() {
		resumeRepository = mock(ResumeRepository.class);
		bulletRepository = mock(ResumeBulletRepository.class);
		embeddingRepository = mock(ResumeBulletEmbeddingRepository.class);
		resumeParser = mock(ResumeParser.class);
		embeddingClient = mock(EmbeddingClient.class);
		llmGuard = mock(LlmGuard.class);

		// 콜백을 그대로 실행하는 트랜잭션 템플릿. 경계 자체는 통합 테스트가 본다.
		TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
		given(transactionTemplate.execute(any())).willAnswer(invocation -> {
			TransactionCallback<?> callback = invocation.getArgument(0);
			return callback.doInTransaction(mock(TransactionStatus.class));
		});

		given(resumeRepository.save(any(Resume.class))).willAnswer(invocation -> {
			Resume resume = invocation.getArgument(0);
			ReflectionTestUtils.setField(resume, "id", UUID.randomUUID());
			return resume;
		});
		given(bulletRepository.saveAllAndFlush(any())).willAnswer(invocation -> {
			List<ResumeBullet> bullets = invocation.getArgument(0);
			for (ResumeBullet bullet : bullets) {
				ReflectionTestUtils.setField(bullet, "id", UUID.randomUUID());
			}
			return bullets;
		});

		service = new ResumeService(resumeRepository, bulletRepository, embeddingRepository,
				resumeParser, embeddingClient, new TextCipher(KEY, new MockEnvironment()), llmGuard,
				transactionTemplate, Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneOffset.UTC),
				90);
	}

	private void givenParsed(String... texts) {
		List<ResumeParser.ParsedBullet> bullets = new ArrayList<>();
		for (String text : texts) {
			bullets.add(new ResumeParser.ParsedBullet("토스", "2022.03 ~", text));
		}
		given(resumeParser.parse(anyString())).willReturn(new ResumeParser.ParsedResume(bullets));
	}

	private void givenEmbeddings(int count) {
		List<float[]> vectors = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			vectors.add(new float[] { i, i + 1f, i + 2f });
		}
		given(embeddingClient.embedAll(any())).willReturn(vectors);
	}

	@Test
	@DisplayName("문장을 저장하고 문장마다 벡터를 채운다")
	void storesBulletsAndEmbeddings() {
		givenParsed("결제 서버 개발", "CI/CD 파이프라인 구축");
		givenEmbeddings(2);

		ResumeService.Stored stored = service.upload(OWNER, RAW);

		assertThat(stored.bulletCount()).isEqualTo(2);
		then(embeddingRepository).should(org.mockito.Mockito.times(2))
			.updateEmbedding(any(UUID.class), any(float[].class));
	}

	@Test
	@DisplayName("원문을 암호화해 저장한다 — raw_text 에 평문이 남지 않는다")
	void encryptsRawText() {
		givenParsed("결제 서버 개발");
		givenEmbeddings(1);

		service.upload(OWNER, RAW);

		ArgumentCaptor<Resume> saved = ArgumentCaptor.forClass(Resume.class);
		then(resumeRepository).should().save(saved.capture());
		assertThat(saved.getValue().getRawText()).doesNotContain("결제 서버").startsWith("v1.");
	}

	@Test
	@DisplayName("TTL 을 걸어 저장한다 — 만료 정리가 집을 수 있어야 한다")
	void setsExpiry() {
		givenParsed("결제 서버 개발");
		givenEmbeddings(1);

		service.upload(OWNER, RAW);

		ArgumentCaptor<Resume> saved = ArgumentCaptor.forClass(Resume.class);
		then(resumeRepository).should().save(saved.capture());
		assertThat(saved.getValue().getExpiresAt())
			.isEqualTo(Instant.parse("2026-11-07T00:00:00Z").atOffset(ZoneOffset.UTC));
	}

	@Test
	@DisplayName("벡터 개수가 문장 수와 다르면 저장하지 않는다 — 밀린 인덱스는 사후에 못 찾는다")
	void rejectsMismatchedEmbeddingCount() {
		givenParsed("결제 서버 개발", "CI/CD 파이프라인 구축");
		givenEmbeddings(1);

		assertThatThrownBy(() -> service.upload(OWNER, RAW)).isInstanceOf(LlmException.class);

		then(resumeRepository).should(never()).save(any());
	}

	@Test
	@DisplayName("암호화 키가 없으면 LLM 을 부르기 전에 끊는다 — 돈만 나가고 실패하면 안 된다")
	void failsBeforeSpendingWhenCipherMissing() {
		ResumeService withoutKey = new ResumeService(resumeRepository, bulletRepository,
				embeddingRepository, resumeParser, embeddingClient,
				new TextCipher("", new MockEnvironment()), llmGuard, mock(TransactionTemplate.class),
				Clock.systemUTC(), 90);

		assertThatThrownBy(() -> withoutKey.upload(OWNER, RAW))
			.isInstanceOf(TextCipher.NotConfiguredException.class);

		then(llmGuard).should(never()).checkAndConsume(anyString());
		then(resumeParser).should(never()).parse(anyString());
	}

	@Test
	@DisplayName("한도를 먼저 소비한다 — 이력서는 캐시가 없어 조건 없이 돈이 나간다")
	void consumesRateLimit() {
		givenParsed("결제 서버 개발");
		givenEmbeddings(1);

		service.upload(OWNER, RAW);

		then(llmGuard).should().checkAndConsume(OWNER);
	}

	@Test
	@DisplayName("문장 순서를 sortOrder 로 보존한다")
	void preservesOrder() {
		givenParsed("첫 번째", "두 번째", "세 번째");
		givenEmbeddings(3);

		service.upload(OWNER, RAW);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ResumeBullet>> saved = ArgumentCaptor.forClass(List.class);
		then(bulletRepository).should().saveAllAndFlush(saved.capture());

		List<ResumeBullet> bullets = saved.getValue();
		assertThat(bullets).extracting(ResumeBullet::getText)
			.containsExactly("첫 번째", "두 번째", "세 번째");
		assertThat(bullets).extracting(ResumeBullet::getSortOrder).containsExactly(0, 1, 2);
	}
}
