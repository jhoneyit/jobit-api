package com.jobit.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.jobit.common.NotFoundException;
import com.jobit.gap.GapItemRepository;
import com.jobit.jd.JobPosting;
import com.jobit.jd.RequirementRepository;
import com.jobit.question.QuestionGenPrompts;
import com.jobit.question.QuestionRepository;
import com.jobit.resume.ResumeRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 이력 목록의 집계 규칙.
 *
 * <p>여기서 지키려는 것은 <b>줄 수와 무관하게 집계 쿼리가 상수 번</b> 나간다는 것이다 (§4.6).
 * 목록 한 줄에 붙는 집계가 셋이라, N+1이 되면 20줄짜리 화면 한 번에 60번이 나간다.
 * 통합 테스트가 아니라 목으로 고정하는 이유는 <b>호출 횟수</b>가 검증 대상이기 때문이다.
 */
@ExtendWith(MockitoExtension.class)
class JdSubmissionServiceTest {

	private static final String OWNER = "user:abc123";

	@Mock
	private JdSubmissionRepository submissionRepository;

	@Mock
	private GapItemRepository gapItemRepository;

	@Mock
	private ResumeRepository resumeRepository;

	@Mock
	private RequirementRepository requirementRepository;

	@Mock
	private QuestionRepository questionRepository;

	@InjectMocks
	private JdSubmissionService service;

	private UUID postingA;

	private UUID postingB;

	@BeforeEach
	void setUp() {
		postingA = UUID.randomUUID();
		postingB = UUID.randomUUID();
	}

	private JdSubmission submission(UUID jobPostingId) {
		JobPosting posting = new JobPosting("hash-" + jobPostingId, "본문", null, "토스", "백엔드",
				"{\"stack\":[\"Java\"]}");
		ReflectionTestUtils.setField(posting, "id", jobPostingId);

		JdSubmission submission = new JdSubmission(OWNER, posting);
		ReflectionTestUtils.setField(submission, "id", UUID.randomUUID());
		return submission;
	}

	/**
	 * {@code (jobPostingId, count)} 집계 행. 리포지토리가 {@code List<Object[]>}를 주기 때문인데,
	 * {@code List.of(new Object[]{...})}는 varargs 로 펼쳐져 {@code List<Object>}가 되므로
	 * 배열 하나를 원소로 담으려면 이렇게 감싸야 한다.
	 */
	private static List<Object[]> countRows(Object[]... rows) {
		return List.of(rows);
	}

	private static Object[] countRow(UUID jobPostingId, long count) {
		return new Object[] { jobPostingId, count };
	}

	private void givenPage(JdSubmission... submissions) {
		given(submissionRepository.findByOwner(eq(OWNER), any(Pageable.class)))
			.willReturn(new PageImpl<>(List.of(submissions), PageRequest.of(0, 20),
					submissions.length));
	}

	@Test
	@DisplayName("줄이 여럿이어도 집계 쿼리는 종류당 한 번씩만 나간다")
	void aggregatesInBatch() {
		givenPage(submission(postingA), submission(postingB));
		given(requirementRepository.countByJobPosting(anyCollection()))
			.willReturn(countRows(countRow(postingA, 12), countRow(postingB, 7)));
		given(questionRepository.countByJobPosting(anyCollection(), anyString()))
			.willReturn(countRows(countRow(postingA, 10)));
		given(resumeRepository.findByOwnerKeyOrderByCreatedAtDesc(OWNER)).willReturn(List.of());

		Page<SubmissionListItem> page = service.list(OWNER, PageRequest.of(0, 20));

		assertThat(page.getContent()).hasSize(2);
		then(requirementRepository).should(times(1)).countByJobPosting(anyCollection());
		then(questionRepository).should(times(1)).countByJobPosting(anyCollection(), anyString());
	}

	@Test
	@DisplayName("집계에 없는 공고는 0이다 — group by 는 0행을 만들지 않는다")
	void defaultsMissingCountsToZero() {
		givenPage(submission(postingA), submission(postingB));
		given(requirementRepository.countByJobPosting(anyCollection()))
			.willReturn(countRows(countRow(postingA, 12)));
		// postingB 는 질문도 요구사항도 없다 → 두 집계 모두 행이 없다
		given(questionRepository.countByJobPosting(anyCollection(), anyString()))
			.willReturn(countRows(countRow(postingA, 10)));
		given(resumeRepository.findByOwnerKeyOrderByCreatedAtDesc(OWNER)).willReturn(List.of());

		List<SubmissionListItem> items = service.list(OWNER, PageRequest.of(0, 20)).getContent();

		assertThat(items).extracting(SubmissionListItem::jobPostingId,
				SubmissionListItem::requirementCount, SubmissionListItem::questionCount)
			.containsExactly(tuple(postingA, 12L, 10L), tuple(postingB, 0L, 0L));
	}

	@Test
	@DisplayName("질문 수는 현재 프롬프트 버전만 센다 — 옛 세트를 더하면 화면 개수와 어긋난다")
	void countsQuestionsForCurrentPromptVersionOnly() {
		givenPage(submission(postingA));
		given(requirementRepository.countByJobPosting(anyCollection())).willReturn(List.of());
		given(questionRepository.countByJobPosting(anyCollection(), anyString()))
			.willReturn(List.of());
		given(resumeRepository.findByOwnerKeyOrderByCreatedAtDesc(OWNER)).willReturn(List.of());

		service.list(OWNER, PageRequest.of(0, 20));

		ArgumentCaptor<String> version = ArgumentCaptor.forClass(String.class);
		then(questionRepository).should().countByJobPosting(anyCollection(), version.capture());
		assertThat(version.getValue()).isEqualTo(QuestionGenPrompts.PROMPT_VERSION);
	}

	@Test
	@DisplayName("이력이 없으면 집계 쿼리를 아예 부르지 않는다 — in () 에 빈 목록을 넘기지 않는다")
	void skipsAggregationWhenEmpty() {
		givenPage();

		assertThat(service.list(OWNER, PageRequest.of(0, 20)).getContent()).isEmpty();

		then(requirementRepository).should(never()).countByJobPosting(anyCollection());
		then(questionRepository).should(never()).countByJobPosting(anyCollection(), anyString());
		then(resumeRepository).should(never()).findByOwnerKeyOrderByCreatedAtDesc(anyString());
	}

	@Test
	@DisplayName("이력서가 없으면 갭 집계를 부르지 않고 gapSummary 는 null 로 남는다")
	void leavesGapSummaryNullWithoutResume() {
		givenPage(submission(postingA));
		given(requirementRepository.countByJobPosting(anyCollection())).willReturn(List.of());
		given(questionRepository.countByJobPosting(anyCollection(), anyString()))
			.willReturn(List.of());
		given(resumeRepository.findByOwnerKeyOrderByCreatedAtDesc(OWNER)).willReturn(List.of());

		List<SubmissionListItem> items = service.list(OWNER, PageRequest.of(0, 20)).getContent();

		assertThat(items.getFirst().gapSummary()).isNull();
		assertThat(items.getFirst().analyzed()).isFalse();
		then(gapItemRepository).should(never()).countByStatus(any(), any());
	}

	@Test
	@DisplayName("접두사 없는 owner_key 는 조회 전에 걸린다")
	void rejectsInvalidOwnerKey() {
		assertThatThrownBy(() -> service.list("접두사없음", PageRequest.of(0, 20)))
			.isInstanceOf(IllegalArgumentException.class);

		then(submissionRepository).should(never()).findByOwner(anyString(), any());
	}

	@Test
	@DisplayName("남의 이력을 지우려 하면 NotFound — 소유자를 따로 비교하지 않고 조회 조건으로 막는다")
	void deleteHidesOtherOwnersSubmission() {
		UUID submissionId = UUID.randomUUID();
		given(submissionRepository.findByIdAndOwnerKey(submissionId, OWNER))
			.willReturn(Optional.empty());

		assertThatThrownBy(() -> service.delete(submissionId, OWNER))
			.isInstanceOf(NotFoundException.class);

		then(submissionRepository).should(never()).delete(any());
	}

	@Test
	@DisplayName("내 이력이면 그 줄만 지운다 — job_posting 은 건드리지 않는다")
	void deletesOwnSubmissionOnly() {
		UUID submissionId = UUID.randomUUID();
		JdSubmission mine = submission(postingA);
		given(submissionRepository.findByIdAndOwnerKey(submissionId, OWNER))
			.willReturn(Optional.of(mine));

		service.delete(submissionId, OWNER);

		then(submissionRepository).should().delete(mine);
	}
}
