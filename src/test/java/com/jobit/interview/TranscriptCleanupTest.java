package com.jobit.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.jobit.PostgresTestContainer;
import com.jobit.jd.JobPosting;
import com.jobit.jd.JobPostingRepository;
import com.jobit.question.Question;
import com.jobit.question.QuestionGenPrompts;
import com.jobit.question.QuestionRepository;
import com.jobit.question.QuestionSet;
import com.jobit.question.QuestionSetRepository;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발화 원문 TTL (docs/interview-practice-design.md §7).
 *
 * <p><b>여기서 지키는 것은 스케줄러가 도는 것이 아니라 약속의 내용이다</b> — "보관 기간이 지나면
 * 원문은 지우되 점수와 피드백은 남긴다". 사용자가 상세 화면에서 보게 될 결과로 확인한다.
 * 리포지토리 쿼리 자체는 {@code InterviewPersistenceTest} 가 따로 본다.
 */
@SpringBootTest(properties = { "jobit.interview.questions-per-session=2",
		"jobit.interview.sessions-per-day=0", "jobit.llm.calls-per-hour=0" })
@Import(PostgresTestContainer.class)
@Transactional
class TranscriptCleanupTest {

	private static final String OWNER = "user:transcript-ttl-test";

	@Autowired
	private TranscriptCleanup cleanup;

	@Autowired
	private InterviewService service;

	@Autowired
	private InterviewAnswerRepository answerRepository;

	@Autowired
	private JobPostingRepository jobPostingRepository;

	@Autowired
	private QuestionSetRepository questionSetRepository;

	@Autowired
	private QuestionRepository questionRepository;

	@Autowired
	private EntityManager entityManager;

	@MockitoBean
	private AnswerScorer scorer;

	private JobPosting posting;

	private Question firstQuestion;

	@BeforeEach
	void setUp() {
		posting = jobPostingRepository.save(new JobPosting("hash-" + UUID.randomUUID(), "본문", null,
				"토스", "백엔드", "{}"));
		QuestionSet set = questionSetRepository.save(
				new QuestionSet(posting, QuestionGenPrompts.PROMPT_VERSION, "claude-opus-5"));
		firstQuestion = questionRepository.save(new Question(set, null, "질문 0",
				Question.Category.CS, (short) 3, "[]", "[\"포인트 A\",\"포인트 B\"]", 0));
		questionRepository.save(new Question(set, null, "질문 1", Question.Category.CS, (short) 3,
				"[]", "[\"포인트 C\"]", 1));

		given(scorer.score(any()))
			.willReturn(new AnswerScorer.Score(80, List.of(0, 1), List.of(), "잘 답했습니다."));
	}

	/** 답변 하나를 만들고 만료 시각을 과거로 돌려 놓는다. */
	private UUID answerExpiredLongAgo() {
		InterviewService.StartedSession started = service.start(OWNER, posting.getId());
		InterviewService.ScoredAnswer scored = service.submitAnswer(started.session().getId(),
				OWNER, firstQuestion.getId(), "제가 말한 개인적인 경력 이야기입니다", 40_000);

		// TTL 이 90일이라 시간을 기다릴 수 없다. 만료 시각을 과거로 돌린다.
		entityManager
			.createQuery("update InterviewAnswer a set a.transcriptExpiresAt = :past where a.id = :id")
			.setParameter("past", OffsetDateTime.now().minusDays(1))
			.setParameter("id", scored.answer().getId())
			.executeUpdate();
		entityManager.clear();

		return started.session().getId();
	}

	@Test
	@DisplayName("보관 기간이 지나면 원문은 지우고 점수·피드백은 남긴다 — 기록 기능이 죽으면 안 된다")
	void forgetsTranscriptButKeepsScore() {
		UUID sessionId = answerExpiredLongAgo();

		cleanup.purgeExpiredTranscripts();
		entityManager.clear();

		InterviewService.AnsweredQuestion after = service.detail(sessionId, OWNER)
			.answers()
			.getFirst();

		assertThat(after.answer().getTranscript()).as("개인 발화는 사라져야 한다").isNull();
		assertThat(after.answer().getScore()).as("점수는 남아야 한다").isEqualTo((short) 80);
		assertThat(after.answer().getFeedback()).isEqualTo("잘 답했습니다.");
		assertThat(after.covered()).containsExactly(0, 1);
		// 화면이 "답하지 못함"과 "원문만 지워짐"을 가르는 근거다 (docs/api.md).
		assertThat(after.answer().answered())
			.as("원문이 지워져도 답했다는 사실은 남는다 — 안 그러면 사용자가 자기가 안 한 줄 안다")
			.isTrue();
	}

	@Test
	@DisplayName("아직 만료되지 않은 원문은 건드리지 않는다")
	void keepsUnexpiredTranscripts() {
		InterviewService.StartedSession started = service.start(OWNER, posting.getId());
		service.submitAnswer(started.session().getId(), OWNER, firstQuestion.getId(), "아직 유효한 발화",
				30_000);
		entityManager.flush();

		cleanup.purgeExpiredTranscripts();
		entityManager.clear();

		assertThat(service.detail(started.session().getId(), OWNER)
			.answers()
			.getFirst()
			.answer()
			.getTranscript()).isEqualTo("아직 유효한 발화");
	}

	@Test
	@DisplayName("지울 것이 없어도 조용히 끝난다 — 매일 도는 작업이라 빈 실행이 정상이다")
	void runsCleanlyWithNothingToPurge() {
		cleanup.purgeExpiredTranscripts();

		assertThat(answerRepository.forgetExpiredTranscripts(OffsetDateTime.now())).isZero();
	}

	@Test
	@DisplayName("한 번 지운 원문은 다시 대상이 되지 않는다 — 매일 같은 행을 훑지 않는다")
	void doesNotRescanAlreadyPurgedRows() {
		answerExpiredLongAgo();

		cleanup.purgeExpiredTranscripts();
		entityManager.clear();

		// transcript is not null 조건이 있어야 이미 지운 행이 다시 잡히지 않는다.
		assertThat(answerRepository.forgetExpiredTranscripts(OffsetDateTime.now())).isZero();
	}
}
