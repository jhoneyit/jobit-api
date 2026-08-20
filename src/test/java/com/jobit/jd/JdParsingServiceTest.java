package com.jobit.jd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.jobit.llm.EmbeddingClient;
import com.jobit.llm.LlmGuard;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 파싱 시 요구사항 임베딩의 규칙 (스펙 §5).
 *
 * <p>고정하는 성질 하나: <b>임베딩 실패가 파싱을 죽이지 않는다.</b> 임베딩은 질문 은행
 * enrichment 라, 여기서 던지면 방금 성공한 LLM 파싱까지 사용자가 잃는다.
 */
class JdParsingServiceTest {

	private JobPostingRepository jobPostingRepository;

	private RequirementRepository requirementRepository;

	private RequirementEmbeddingRepository embeddingRepository;

	private JdParser jdParser;

	private EmbeddingClient embeddingClient;

	private JdParsingService service;

	@BeforeEach
	void setUp() {
		jobPostingRepository = mock(JobPostingRepository.class);
		requirementRepository = mock(RequirementRepository.class);
		embeddingRepository = mock(RequirementEmbeddingRepository.class);
		jdParser = mock(JdParser.class);
		embeddingClient = mock(EmbeddingClient.class);

		given(jobPostingRepository.findByContentHash(anyString())).willReturn(Optional.empty());
		given(jobPostingRepository.save(any(JobPosting.class))).willAnswer(invocation -> {
			JobPosting posting = invocation.getArgument(0);
			ReflectionTestUtils.setField(posting, "id", UUID.randomUUID());
			return posting;
		});
		given(requirementRepository.saveAllAndFlush(any())).willAnswer(invocation -> {
			List<Requirement> requirements = invocation.getArgument(0);
			for (Requirement requirement : requirements) {
				ReflectionTestUtils.setField(requirement, "id", UUID.randomUUID());
			}
			return requirements;
		});
		given(jdParser.parse(anyString())).willReturn(new JdParser.ParsedJd("회사", "직함", "{}",
				List.of(new JdParser.ParsedRequirement("요구사항 A", Requirement.Kind.REQUIRED,
						List.of()),
						new JdParser.ParsedRequirement("요구사항 B", Requirement.Kind.PREFERRED,
								List.of()))));

		service = new JdParsingService(jobPostingRepository, requirementRepository,
				embeddingRepository, jdParser, embeddingClient, mock(LlmGuard.class));
	}

	@Test
	@DisplayName("정상이면 요구사항마다 임베딩이 저장된다 — 질문 은행의 검색 축이다")
	void embedsEveryRequirement() {
		given(embeddingClient.embedAll(any()))
			.willReturn(List.of(new float[] { 1f }, new float[] { 1f }));

		service.parseOrGetCached("공고 본문", null, "user:u-1");

		then(embeddingRepository).should(times(2)).updateEmbedding(any(), any());
	}

	@Test
	@DisplayName("임베딩이 실패해도 파싱 결과는 살아남는다 — 은행에서만 빠진다")
	void embeddingFailureDoesNotKillParsing() {
		given(embeddingClient.embedAll(any())).willThrow(new RuntimeException("Ollama down"));

		JdParsingService.Outcome outcome = service.parseOrGetCached("공고 본문", null, "user:u-1");

		assertThat(outcome.cached()).isFalse();
		assertThat(outcome.jobPosting()).isNotNull();
		then(embeddingRepository).should(never()).updateEmbedding(any(), any());
	}

	@Test
	@DisplayName("벡터 수가 어긋나면 하나도 저장하지 않는다 — 어긋난 축은 없느니만 못하다")
	void countMismatchSkipsAllEmbeddings() {
		given(embeddingClient.embedAll(any())).willReturn(List.of(new float[] { 1f }));

		service.parseOrGetCached("공고 본문", null, "user:u-1");

		then(embeddingRepository).should(never()).updateEmbedding(any(), any());
	}
}
