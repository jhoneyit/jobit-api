package com.jobit.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.jobit.PostgresTestContainer;
import com.jobit.common.NotFoundException;
import com.jobit.jd.JobPosting;
import com.jobit.jd.JobPostingRepository;
import com.jobit.question.Question;
import com.jobit.question.QuestionGenPrompts;
import com.jobit.question.QuestionRepository;
import com.jobit.question.QuestionSet;
import com.jobit.question.QuestionSetRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 세션 흐름 (docs/interview-practice-design.md §4).
 *
 * <p><b>목이 아니라 실제 DB로 돈다.</b> 이 흐름의 어려운 부분이 전부 DB 제약과 맞물려 있기
 * 때문이다 — {@code answered_count}가 {@code question_count}를 넘지 않는 것, 재제출이 행을
 * 덮어쓰는 것, 총점의 분모. 목으로는 이것들이 지켜지는지 알 수 없다.
 *
 * <p>{@link AnswerScorer}만 목이다. 실제 LLM 호출은 스모크 테스트의 몫이고, 여기서 확인할 것은
 * <b>언제 부르고 언제 부르지 않는가</b>다.
 */
@SpringBootTest(properties = { "jobit.interview.questions-per-session=3",
		"jobit.interview.sessions-per-day=2", "jobit.interview.time-limit-sec=90",
		// LLM 호출 한도는 이 테스트의 관심사가 아니다. 세션 상한만 본다.
		"jobit.llm.calls-per-hour=0" })
@Import(PostgresTestContainer.class)
@Transactional
class InterviewServiceTest {

	private static final String OWNER = "user:interview-service-test";

	@Autowired
	private InterviewService service;

	@Autowired
	private InterviewSessionRepository sessionRepository;

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

	private List<Question> questions;

	@BeforeEach
	void setUp() {
		posting = jobPostingRepository.save(new JobPosting("hash-" + UUID.randomUUID(), "본문", null,
				"토스", "백엔드 개발자", "{\"stack\":[\"Java\"]}"));
		// PROMPT_VERSION 이 맞아야 start() 가 세트를 찾는다 — 재생성 판단과 같은 키다.
		QuestionSet set = questionSetRepository.save(
				new QuestionSet(posting, QuestionGenPrompts.PROMPT_VERSION, "claude-opus-5"));

		questions = List.of(saveQuestion(set, 0, "[\"포인트 A\",\"포인트 B\"]"),
				saveQuestion(set, 1, "[\"포인트 C\",\"포인트 D\"]"),
				saveQuestion(set, 2, "[\"포인트 E\",\"포인트 F\"]"),
				saveQuestion(set, 3, "[\"포인트 G\",\"포인트 H\"]"));
	}

	private Question saveQuestion(QuestionSet set, int sortOrder, String outline) {
		return questionRepository.save(new Question(set, null, "질문 " + sortOrder,
				Question.Category.CS, (short) 3, "[]", outline, sortOrder));
	}

	private void givenScore(int score, List<Integer> covered) {
		given(scorer.score(any())).willReturn(
				new AnswerScorer.Score(score, covered, List.of(), "피드백"));
	}

	@Test
	@DisplayName("세션은 설정한 문항 수만큼 앞에서부터 출제한다")
	void startsWithConfiguredQuestionCount() {
		InterviewService.StartedSession started = service.start(OWNER, posting.getId());

		assertThat(started.session().getQuestionCount()).isEqualTo((short) 3);
		assertThat(started.questions()).hasSize(3);
		assertThat(started.questions()).extracting(InterviewService.QuestionPrompt::text)
			.containsExactly("질문 0", "질문 1", "질문 2");
		assertThat(started.questions()).allSatisfy(
				q -> assertThat(q.timeLimitSec()).isEqualTo(90));
	}

	/**
	 * 뼈대가 <b>null 인 질문은 만들 수 없다</b> — V6 이 NOT NULL + {@code DEFAULT '[]'} 로
	 * 바꿨다. 그래서 현실적인 "채점 불가" 상태는 빈 배열이다.
	 */
	@Test
	@DisplayName("뼈대가 빈 질문은 건너뛴다 — 채점할 수 없는 문항이 분모에 들어가면 총점이 부당해진다")
	void skipsQuestionsWithEmptyOutline() {
		QuestionSet set = questionSetRepository.save(new QuestionSet(
				jobPostingRepository.save(new JobPosting("hash-" + UUID.randomUUID(), "본문", null,
						"회사", "직무", "{}")),
				QuestionGenPrompts.PROMPT_VERSION, "claude-opus-5"));
		UUID emptyOutlinePosting = set.getJobPosting().getId();
		saveQuestion(set, 0, "[]");          // 빈 배열 — 채점 기준이 없다
		saveQuestion(set, 1, "\"배열이 아님\""); // 배열이 아닌 jsonb 도 방어한다
		saveQuestion(set, 2, "[\"포인트\"]"); // 이것만 채점 가능

		InterviewService.StartedSession started = service.start(OWNER, emptyOutlinePosting);

		assertThat(started.session().getQuestionCount()).isEqualTo((short) 1);
		assertThat(started.questions()).extracting(InterviewService.QuestionPrompt::text)
			.containsExactly("질문 2");
	}

	@Test
	@DisplayName("채점기가 이상한 인덱스를 줘도 저장 직전에 걸러진다 — 포트 뒤에 무엇이 꽂히든")
	void normalizesScoreAtPersistenceBoundary() {
		// 뼈대가 2개인데 범위 밖 인덱스와 100 초과 점수를 준다.
		given(scorer.score(any()))
			.willReturn(new AnswerScorer.Score(150, List.of(0, 9), List.of(), "피드백"));
		InterviewService.StartedSession started = service.start(OWNER, posting.getId());

		InterviewService.ScoredAnswer scored = service.submitAnswer(started.session().getId(),
				OWNER, questions.getFirst().getId(), "답변", 10_000);

		// 검증된 covered 가 1/2 이라 점수 상한도 50 이다 — 근거 없는 고득점이 저장되지 않는다.
		assertThat(scored.score().score()).as("DB CHECK 제약에 닿기 전에 잘려야 한다").isEqualTo(50);
		assertThat(scored.score().covered()).containsExactly(0);
		assertThat(scored.score().missed()).containsExactly(1);
	}

	@Test
	@DisplayName("질문이 없는 공고면 시작하지 못한다 — 채점 기준이 질문 생성의 산물이다")
	void rejectsPostingWithoutQuestions() {
		JobPosting bare = jobPostingRepository
			.save(new JobPosting("hash-" + UUID.randomUUID(), "본문", null, "회사", "직무", "{}"));

		assertThatThrownBy(() -> service.start(OWNER, bare.getId()))
			.isInstanceOf(InterviewService.QuestionsNotReadyException.class);
	}

	@Test
	@DisplayName("없는 공고면 404")
	void rejectsUnknownPosting() {
		assertThatThrownBy(() -> service.start(OWNER, UUID.randomUUID()))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	@DisplayName("하루 세션 상한을 넘으면 시작하지 못한다 — 이 기능만 세션 1건 = LLM N회다")
	void enforcesDailySessionLimit() {
		service.start(OWNER, posting.getId());
		service.start(OWNER, posting.getId());

		assertThatThrownBy(() -> service.start(OWNER, posting.getId()))
			.isInstanceOf(InterviewService.DailySessionLimitExceededException.class);
	}

	@Test
	@DisplayName("상한은 소유자별이다 — 남이 쓴다고 내가 막히면 안 된다")
	void dailyLimitIsPerOwner() {
		service.start(OWNER, posting.getId());
		service.start(OWNER, posting.getId());

		// 다른 소유자는 그대로 시작된다.
		assertThat(service.start("user:someone-else", posting.getId()).session()).isNotNull();
	}

	@Test
	@DisplayName("답변을 제출하면 채점 결과가 붙고 답변 수가 오른다")
	void submitsAndScores() {
		givenScore(80, List.of(0, 1));
		InterviewService.StartedSession started = service.start(OWNER, posting.getId());
		UUID sessionId = started.session().getId();

		InterviewService.ScoredAnswer scored = service.submitAnswer(sessionId, OWNER,
				questions.getFirst().getId(), "제 답변입니다", 30_000);

		assertThat(scored.score().score()).isEqualTo(80);
		// missed 는 서버가 covered 의 여집합으로 계산한다 — 목이 준 빈 목록이 아니다.
		assertThat(scored.score().missed()).isEmpty();
		assertThat(scored.outline()).containsExactly("포인트 A", "포인트 B");
		assertThat(sessionRepository.findById(sessionId).orElseThrow().getAnsweredCount())
			.isEqualTo((short) 1);
	}

	@Test
	@DisplayName("답하지 못한 문항은 LLM 을 부르지 않는다 — 제한 시간이 있는 이상 정상 경로다")
	void skipsScoringForEmptyTranscript() {
		InterviewService.StartedSession started = service.start(OWNER, posting.getId());

		InterviewService.ScoredAnswer scored = service.submitAnswer(started.session().getId(),
				OWNER, questions.getFirst().getId(), "   ", 90_000);

		then(scorer).should(never()).score(any());
		assertThat(scored.score().score()).isZero();
		assertThat(scored.answer().answered()).isFalse();
		assertThat(scored.score().missed()).containsExactly(0, 1);
	}

	@Test
	@DisplayName("같은 질문에 다시 제출하면 덮어쓰고 답변 수는 늘지 않는다")
	void resubmitDoesNotDoubleCount() {
		givenScore(40, List.of());
		InterviewService.StartedSession started = service.start(OWNER, posting.getId());
		UUID sessionId = started.session().getId();
		UUID questionId = questions.getFirst().getId();

		service.submitAnswer(sessionId, OWNER, questionId, "첫 답변", 10_000);
		givenScore(90, List.of(0, 1));
		InterviewService.ScoredAnswer second = service.submitAnswer(sessionId, OWNER, questionId,
				"다시 말한 답변", 20_000);

		assertThat(second.score().score()).isEqualTo(90);
		assertThat(sessionRepository.findById(sessionId).orElseThrow().getAnsweredCount())
			.as("재제출은 행을 덮어쓰므로 세면 안 된다 — 세면 CHECK 제약에 걸린다")
			.isEqualTo((short) 1);
		assertThat(answerRepository.findBySessionIdOrderBySortOrder(sessionId)).hasSize(1);
	}

	@Test
	@DisplayName("이 세션에 출제되지 않은 질문은 거절한다 — 남의 공고 질문 점수를 심을 수 없다")
	void rejectsQuestionOutsideSession() {
		InterviewService.StartedSession started = service.start(OWNER, posting.getId());

		assertThatThrownBy(() -> service.submitAnswer(started.session().getId(), OWNER,
				UUID.randomUUID(), "답변", 10_000)).isInstanceOf(NotFoundException.class);
	}

	@Test
	@DisplayName("남의 세션에는 답변할 수 없다 — 존재 여부도 알려주지 않는다")
	void rejectsOtherOwnersSession() {
		InterviewService.StartedSession started = service.start(OWNER, posting.getId());

		assertThatThrownBy(() -> service.submitAnswer(started.session().getId(), "user:someone-else",
				questions.getFirst().getId(), "답변", 10_000)).isInstanceOf(NotFoundException.class);
	}

	@Test
	@DisplayName("총점은 출제된 전 문항의 평균이다 — 답한 것만 평균 내면 한 문항만 답하는 쪽이 유리해진다")
	void totalScoreDividesByAllQuestions() {
		givenScore(90, List.of(0, 1));
		InterviewService.StartedSession started = service.start(OWNER, posting.getId());
		UUID sessionId = started.session().getId();

		// 3문항 중 한 문항만 답했다.
		service.submitAnswer(sessionId, OWNER, questions.getFirst().getId(), "답변", 10_000);

		InterviewSession finished = service.finish(sessionId, OWNER);

		assertThat(finished.getTotalScore()).as("90 / 3문항 = 30. 답한 것만 세면 90이 된다")
			.isEqualTo((short) 30);
		assertThat(finished.getAnsweredCount()).isEqualTo((short) 1);
		assertThat(finished.isFinished()).isTrue();
	}

	@Test
	@DisplayName("종료는 멱등이다 — 새로고침이나 제출과의 경합이 오류가 되면 안 된다")
	void finishIsIdempotent() {
		givenScore(60, List.of(0));
		InterviewService.StartedSession started = service.start(OWNER, posting.getId());
		UUID sessionId = started.session().getId();
		service.submitAnswer(sessionId, OWNER, questions.getFirst().getId(), "답변", 10_000);

		InterviewSession first = service.finish(sessionId, OWNER);
		InterviewSession again = service.finish(sessionId, OWNER);

		assertThat(again.getTotalScore()).isEqualTo(first.getTotalScore());
		assertThat(again.getFinishedAt()).isEqualTo(first.getFinishedAt());
	}

	@Test
	@DisplayName("종료된 세션에는 답변할 수 없다")
	void rejectsAnswerAfterFinish() {
		InterviewService.StartedSession started = service.start(OWNER, posting.getId());
		UUID sessionId = started.session().getId();
		service.finish(sessionId, OWNER);

		assertThatThrownBy(() -> service.submitAnswer(sessionId, OWNER,
				questions.getFirst().getId(), "답변", 10_000))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("한 문항도 답하지 않고 끝내면 0점이다")
	void unansweredSessionScoresZero() {
		InterviewService.StartedSession started = service.start(OWNER, posting.getId());

		InterviewSession finished = service.finish(started.session().getId(), OWNER);

		assertThat(finished.getTotalScore()).isZero();
		assertThat(finished.getAnsweredCount()).isZero();
	}

	// ── 기록 조회 ────────────────────────────────────────────────

	@Test
	@DisplayName("상세는 저장된 인덱스를 다시 목록으로 편다 — jsonb 왕복이 실제로 맞물린다")
	void detailReadsBackIndexes() {
		givenScore(70, List.of(1));
		InterviewService.StartedSession started = service.start(OWNER, posting.getId());
		UUID sessionId = started.session().getId();
		service.submitAnswer(sessionId, OWNER, questions.getFirst().getId(), "답변", 10_000);

		InterviewService.SessionDetail detail = service.detail(sessionId, OWNER);

		// 이어서 하기용 문항 목록이 함께 온다 — 새로고침해도 세션이 미아가 되지 않는다.
		assertThat(detail.questions()).hasSize(3);
		assertThat(detail.answers()).hasSize(1);
		InterviewService.AnsweredQuestion first = detail.answers().getFirst();
		assertThat(first.covered()).containsExactly(1);
		assertThat(first.missed()).containsExactly(0);
		assertThat(first.outline()).containsExactly("포인트 A", "포인트 B");
		assertThat(first.answer().getTranscript()).isEqualTo("답변");
	}

	@Test
	@DisplayName("답하지 않은 문항도 상세에 남는다 — 무응답 자체가 결과다")
	void detailIncludesUnansweredQuestions() {
		InterviewService.StartedSession started = service.start(OWNER, posting.getId());
		UUID sessionId = started.session().getId();
		service.submitAnswer(sessionId, OWNER, questions.getFirst().getId(), "", 90_000);

		InterviewService.AnsweredQuestion only = service.detail(sessionId, OWNER)
			.answers()
			.getFirst();

		assertThat(only.answer().answered()).isFalse();
		assertThat(only.answer().getTranscript()).isNull();
		assertThat(only.missed()).containsExactly(0, 1);
	}

	@Test
	@DisplayName("목록은 내 것만, 최근순으로")
	void listsOwnSessionsOnly() {
		service.start(OWNER, posting.getId());
		service.start("user:someone-else", posting.getId());

		assertThat(service.list(OWNER, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);
	}

	@Test
	@DisplayName("남의 기록 상세는 열리지 않는다")
	void hidesOtherOwnersDetail() {
		InterviewService.StartedSession started = service.start(OWNER, posting.getId());

		assertThatThrownBy(() -> service.detail(started.session().getId(), "user:someone-else"))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	@DisplayName("기록을 지우면 답변도 함께 지워진다 — 세션 없이 남은 답변은 고아다")
	void deleteCascadesToAnswers() {
		givenScore(50, List.of(0));
		InterviewService.StartedSession started = service.start(OWNER, posting.getId());
		UUID sessionId = started.session().getId();
		service.submitAnswer(sessionId, OWNER, questions.getFirst().getId(), "답변", 10_000);

		service.delete(sessionId, OWNER);
		entityManager.flush();
		entityManager.clear();

		assertThat(sessionRepository.findById(sessionId)).isEmpty();
		assertThat(answerRepository.findBySessionIdOrderBySortOrder(sessionId)).isEmpty();
		// 질문과 공고는 공유 자산이라 남아야 한다.
		assertThat(questionRepository.findById(questions.getFirst().getId())).isPresent();
		assertThat(jobPostingRepository.findById(posting.getId())).isPresent();
	}

	@Test
	@DisplayName("남의 기록은 지워지지 않는다")
	void cannotDeleteOtherOwnersRecord() {
		InterviewService.StartedSession started = service.start(OWNER, posting.getId());

		assertThatThrownBy(() -> service.delete(started.session().getId(), "user:someone-else"))
			.isInstanceOf(NotFoundException.class);
		assertThat(sessionRepository.findById(started.session().getId())).isPresent();
	}

	@Test
	@DisplayName("익명 기록을 계정으로 승계한다 — 같은 공고가 양쪽에 있어도 충돌하지 않는다")
	void transfersOwnership() {
		service.start("anon:sess-1", posting.getId());
		// 제출 이력이라면 유니크 제약에 걸릴 상황이다. 세션은 같은 공고로 몇 번이든 할 수 있다.
		service.start("user:target", posting.getId());

		int moved = service.transferOwnership("anon:sess-1", "user:target");

		assertThat(moved).isEqualTo(1);
		assertThat(service.list("user:target", PageRequest.of(0, 20)).getTotalElements())
			.isEqualTo(2);
		assertThat(service.list("anon:sess-1", PageRequest.of(0, 20)).getTotalElements()).isZero();
	}

	@Test
	@DisplayName("같은 소유자끼리는 옮기지 않는다")
	void skipsSelfTransfer() {
		assertThat(service.transferOwnership(OWNER, OWNER)).isZero();
	}

	@Test
	@DisplayName("발화 원문에 TTL 이 붙는다 — 개인정보라 무기한 보관하지 않는다")
	void setsTranscriptExpiry() {
		givenScore(50, List.of(0));
		InterviewService.StartedSession started = service.start(OWNER, posting.getId());

		InterviewService.ScoredAnswer scored = service.submitAnswer(started.session().getId(),
				OWNER, questions.getFirst().getId(), "답변", 10_000);

		assertThat(scored.answer().getTranscriptExpiresAt()).isNotNull().isAfter(
				java.time.OffsetDateTime.now().plusDays(89));
	}
}
