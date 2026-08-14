package com.jobit.gap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.jobit.common.NotFoundException;
import com.jobit.jd.JobPosting;
import com.jobit.jd.JobPostingRepository;
import com.jobit.jd.Requirement;
import com.jobit.jd.RequirementRepository;
import com.jobit.llm.EmbeddingClient;
import com.jobit.llm.LlmException;
import com.jobit.llm.LlmGuard;
import com.jobit.resume.Resume;
import com.jobit.resume.ResumeBulletEmbeddingRepository;
import com.jobit.resume.ResumeBulletRepository;
import com.jobit.resume.ResumeRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 갭 분석 흐름의 규칙 (스펙 §4.3).
 *
 * <p>{@code ResumeServiceTest} 와 같은 원칙 — 고정하는 것은 대부분 <b>순서와 조건</b>이다.
 * 캐시 적중이 한도를 소비하면 돈이 새고, 임베딩 없는 이력서가 통과하면 전부 MISSING 이
 * 캐시에 굳는다.
 */
class GapAnalysisServiceTest {

	private static final String OWNER = "user:u-1";

	private static final UUID RESUME_ID = UUID.randomUUID();

	private static final UUID POSTING_ID = UUID.randomUUID();

	private GapAnalysisRepository gapAnalysisRepository;

	private GapItemRepository gapItemRepository;

	private ResumeRepository resumeRepository;

	private ResumeBulletRepository bulletRepository;

	private ResumeBulletEmbeddingRepository embeddingRepository;

	private JobPostingRepository jobPostingRepository;

	private RequirementRepository requirementRepository;

	private GapJudge gapJudge;

	private EmbeddingClient embeddingClient;

	private LlmGuard llmGuard;

	private GapAnalysisService service;

	private Resume resume;

	private JobPosting jobPosting;

	@BeforeEach
	void setUp() {
		gapAnalysisRepository = mock(GapAnalysisRepository.class);
		gapItemRepository = mock(GapItemRepository.class);
		resumeRepository = mock(ResumeRepository.class);
		bulletRepository = mock(ResumeBulletRepository.class);
		embeddingRepository = mock(ResumeBulletEmbeddingRepository.class);
		jobPostingRepository = mock(JobPostingRepository.class);
		requirementRepository = mock(RequirementRepository.class);
		gapJudge = mock(GapJudge.class);
		embeddingClient = mock(EmbeddingClient.class);
		llmGuard = mock(LlmGuard.class);

		// 콜백을 그대로 실행하는 트랜잭션 템플릿. 경계 자체는 통합 테스트가 본다.
		TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
		given(transactionTemplate.execute(any())).willAnswer(invocation -> {
			TransactionCallback<?> callback = invocation.getArgument(0);
			return callback.doInTransaction(mock(TransactionStatus.class));
		});

		resume = mock(Resume.class);
		given(resume.getId()).willReturn(RESUME_ID);
		jobPosting = mock(JobPosting.class);
		given(jobPosting.getId()).willReturn(POSTING_ID);

		given(resumeRepository.findByIdAndOwnerKey(RESUME_ID, OWNER))
			.willReturn(Optional.of(resume));
		given(jobPostingRepository.findById(POSTING_ID)).willReturn(Optional.of(jobPosting));
		given(gapAnalysisRepository.findByResumeIdAndJobPostingId(RESUME_ID, POSTING_ID))
			.willReturn(Optional.empty());
		given(embeddingRepository.countWithEmbedding(RESUME_ID)).willReturn(5);

		given(gapAnalysisRepository.save(any(GapAnalysis.class))).willAnswer(invocation -> {
			GapAnalysis analysis = invocation.getArgument(0);
			ReflectionTestUtils.setField(analysis, "id", UUID.randomUUID());
			return analysis;
		});
		given(gapItemRepository.findForDisplay(any())).willReturn(List.of());

		service = new GapAnalysisService(gapAnalysisRepository, gapItemRepository,
				resumeRepository, bulletRepository, embeddingRepository, jobPostingRepository,
				requirementRepository, gapJudge, embeddingClient, llmGuard, transactionTemplate);
	}

	private Requirement requirement(String text) {
		Requirement requirement = mock(Requirement.class);
		given(requirement.getText()).willReturn(text);
		return requirement;
	}

	private void givenRequirements(String... texts) {
		List<Requirement> requirements = new java.util.ArrayList<>();
		for (String text : texts) {
			requirements.add(requirement(text));
		}
		given(requirementRepository.findByJobPostingIdOrderBySortOrder(POSTING_ID))
			.willReturn(requirements);
		given(embeddingClient.embedAll(any()))
			.willReturn(java.util.Collections.nCopies(texts.length, new float[] { 0.1f }));
	}

	@Test
	@DisplayName("캐시 적중이면 LLM 도 한도도 타지 않는다 — 재분석은 공짜여야 한다")
	void cacheHitSkipsEverything() {
		GapAnalysis existing = mock(GapAnalysis.class);
		given(gapAnalysisRepository.findByResumeIdAndJobPostingId(RESUME_ID, POSTING_ID))
			.willReturn(Optional.of(existing));

		GapAnalysisService.Result result = service.analyze(OWNER, RESUME_ID, POSTING_ID);

		assertThat(result.cached()).isTrue();
		then(llmGuard).should(never()).checkAndConsume(anyString());
		then(gapJudge).should(never()).judge(any());
		then(embeddingClient).should(never()).embedAll(any());
	}

	@Test
	@DisplayName("임베딩 없는 이력서는 한도를 소비하기 전에 끊는다 — 전부 MISSING 이 캐시에 굳으면 안 된다")
	void rejectsResumeWithoutEmbeddings() {
		givenRequirements("요구사항 하나");
		given(embeddingRepository.countWithEmbedding(RESUME_ID)).willReturn(0);

		assertThatThrownBy(() -> service.analyze(OWNER, RESUME_ID, POSTING_ID))
			.isInstanceOf(GapAnalysisService.ResumeNotAnalyzableException.class);

		then(llmGuard).should(never()).checkAndConsume(anyString());
		then(gapJudge).should(never()).judge(any());
	}

	@Test
	@DisplayName("요구사항마다 후보 3개로 판정한다 — 분석 1건 = 한도 1회")
	void judgesEveryRequirementWithTopCandidates() {
		givenRequirements("요구사항 A", "요구사항 B");
		given(embeddingRepository.findNearest(eq(RESUME_ID), any(), anyInt()))
			.willReturn(List.of(new ResumeBulletEmbeddingRepository.Neighbor(UUID.randomUUID(),
					"문장", null, null, 0.9)));
		given(gapJudge.judge(any()))
			.willReturn(new GapJudge.Verdict(GapItem.Status.MISSING, null, "근거 없음"));

		GapAnalysisService.Result result = service.analyze(OWNER, RESUME_ID, POSTING_ID);

		assertThat(result.cached()).isFalse();
		then(llmGuard).should().checkAndConsume(OWNER);
		then(gapJudge).should(org.mockito.Mockito.times(2)).judge(any());
		then(embeddingRepository).should(org.mockito.Mockito.times(2)).findNearest(eq(RESUME_ID),
				any(), eq(GapAnalysisService.CANDIDATE_LIMIT));

		ArgumentCaptor<List<GapItem>> saved = ArgumentCaptor.forClass(List.class);
		then(gapItemRepository).should().saveAll(saved.capture());
		assertThat(saved.getValue()).hasSize(2);
	}

	@Test
	@DisplayName("요구사항 수와 벡터 수가 어긋나면 판정하지 않는다 — 남의 벡터로 후보를 뽑게 된다")
	void rejectsVectorCountMismatch() {
		givenRequirements("요구사항 A", "요구사항 B");
		given(embeddingClient.embedAll(any())).willReturn(List.of(new float[] { 0.1f }));

		assertThatThrownBy(() -> service.analyze(OWNER, RESUME_ID, POSTING_ID))
			.isInstanceOf(LlmException.class);

		then(gapJudge).should(never()).judge(any());
	}

	@Test
	@DisplayName("동시 분석 경합이면 먼저 저장된 결과를 돌려준다 — 유니크 제약이 최종 방어선이다")
	void returnsWinnerOnConflict() {
		givenRequirements("요구사항 A");
		given(embeddingRepository.findNearest(eq(RESUME_ID), any(), anyInt()))
			.willReturn(List.of(new ResumeBulletEmbeddingRepository.Neighbor(UUID.randomUUID(),
					"문장", null, null, 0.9)));
		given(gapJudge.judge(any()))
			.willReturn(new GapJudge.Verdict(GapItem.Status.MISSING, null, "근거 없음"));

		GapAnalysis winner = mock(GapAnalysis.class);
		given(gapAnalysisRepository.save(any(GapAnalysis.class)))
			.willThrow(new DataIntegrityViolationException("duplicate"));
		// 첫 조회(캐시 미스)는 빈 값, 경합 후 재조회는 이긴 쪽을 돌려준다.
		given(gapAnalysisRepository.findByResumeIdAndJobPostingId(RESUME_ID, POSTING_ID))
			.willReturn(Optional.empty(), Optional.of(winner));

		GapAnalysisService.Result result = service.analyze(OWNER, RESUME_ID, POSTING_ID);

		assertThat(result.cached()).isTrue();
		assertThat(result.analysis()).isSameAs(winner);
	}

	@Test
	@DisplayName("남의 이력서면 존재도 알려주지 않는다 — 조회 조건에 소유자가 들어간다")
	void hidesOthersResume() {
		assertThatThrownBy(() -> service.analyze("user:someone-else", RESUME_ID, POSTING_ID))
			.isInstanceOf(NotFoundException.class);

		then(llmGuard).should(never()).checkAndConsume(anyString());
	}

	@Test
	@DisplayName("GET 조회는 분석을 시작하지 않는다 — 없으면 404 다")
	void getNeverStartsAnalysis() {
		assertThatThrownBy(() -> service.getExisting(OWNER, RESUME_ID, POSTING_ID))
			.isInstanceOf(NotFoundException.class);

		then(gapJudge).should(never()).judge(any());
		then(llmGuard).should(never()).checkAndConsume(anyString());
	}
}
