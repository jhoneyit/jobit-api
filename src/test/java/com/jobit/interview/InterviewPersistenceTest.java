package com.jobit.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobit.PostgresTestContainer;
import com.jobit.jd.JobPosting;
import com.jobit.jd.JobPostingRepository;
import com.jobit.question.Question;
import com.jobit.question.QuestionRepository;
import com.jobit.question.QuestionSet;
import com.jobit.question.QuestionSetRepository;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * V9 스키마와 면접 연습 엔티티가 실제로 맞물리는지 (docs/interview-practice-design.md §3).
 *
 * <p><b>{@code contextLoads()}가 있는데 왜 또 필요한가.</b> 그쪽은
 * {@code ddl-auto=validate} 덕분에 <b>컬럼이 있는지</b>까지 본다. 하지만 거기까지다 —
 * 실제로 저장하고 읽었을 때 jsonb 가 왕복하는지, 유니크 제약이 의도대로 걸리는지,
 * TTL 갱신 쿼리가 원하는 행만 고르는지는 값을 한 번 통과시켜 봐야 안다.
 *
 * <p>즉 여기서 고정하는 것은 매핑이 아니라 스키마의 <b>의도</b>다. 주석에만 적어 둔 규칙은
 * 지켜지지 않는다.
 */
@SpringBootTest
@Import(PostgresTestContainer.class)
@Transactional
class InterviewPersistenceTest {

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

	private static final String OWNER = "user:interview-test";

	private JobPosting posting;

	private QuestionSet questionSet;

	private Question question;

	@BeforeEach
	void setUp() {
		// content_hash 가 유니크라 테스트마다 다른 값을 쓴다.
		posting = jobPostingRepository.save(new JobPosting("hash-" + UUID.randomUUID(),
				"채용공고 본문", null, "토스", "백엔드 개발자", "{\"stack\":[\"Java\"]}"));
		questionSet = questionSetRepository
			.save(new QuestionSet(posting, "test-" + UUID.randomUUID(), "claude-opus-5"));
		question = questionRepository.save(new Question(questionSet, null, "트랜잭션 격리 수준을 설명해 주세요.",
				Question.Category.CS, (short) 3, "[\"어느 수준까지 써봤나요?\"]",
				"[\"격리 수준 4가지\",\"팬텀 리드\",\"실무 선택 기준\"]", 0));
	}

	private InterviewSession newSession(int questionCount) {
		return sessionRepository
			.save(new InterviewSession(OWNER, posting, questionSet, questionCount));
	}

	private InterviewAnswer newAnswer(InterviewSession session, String transcript) {
		return answerRepository.save(new InterviewAnswer(session, question, 0, transcript, 42_000,
				90, OffsetDateTime.now().plusDays(90)));
	}

	@Test
	@DisplayName("세션과 답변이 저장되고 읽힌다 — 컬럼 매핑이 V9 와 맞는다")
	void persistsAndReadsBack() {
		InterviewSession session = newSession(5);
		newAnswer(session, "격리 수준은 네 가지가 있고...");

		entityManager.flush();
		entityManager.clear();

		InterviewSession found = sessionRepository.findById(session.getId()).orElseThrow();
		assertThat(found.getOwnerKey()).isEqualTo(OWNER);
		assertThat(found.getQuestionCount()).isEqualTo((short) 5);
		assertThat(found.getAnsweredCount()).isEqualTo((short) 0);
		assertThat(found.getTotalScore()).isNull();
		assertThat(found.getFinishedAt()).isNull();
		// insertable=false 인 컬럼들이 DB 기본값으로 채워져 돌아와야 한다.
		assertThat(found.getStartedAt()).isNotNull();
		assertThat(found.getCreatedAt()).isNotNull();

		List<InterviewAnswer> answers = answerRepository.findForDisplay(session.getId());
		assertThat(answers).hasSize(1);
		assertThat(answers.getFirst().getTranscript()).startsWith("격리 수준은");
		assertThat(answers.getFirst().getTimeLimitSec()).isEqualTo((short) 90);
		assertThat(answers.getFirst().getQuestion().getText()).contains("트랜잭션");
	}

	@Test
	@DisplayName("covered/missed 는 jsonb 배열로 왕복한다 — 인덱스로 저장하기로 한 결정이 실제로 선다")
	void roundTripsOutlineIndexesAsJsonb() {
		InterviewSession session = newSession(1);
		InterviewAnswer answer = newAnswer(session, "격리 수준은 네 가지입니다.");
		answer.applyScore(72, "[0,2]", "[1]", "핵심은 짚었지만 실무 기준이 빠졌습니다.",
				OffsetDateTime.now());

		entityManager.flush();
		entityManager.clear();

		InterviewAnswer found = answerRepository.findById(answer.getId()).orElseThrow();
		assertThat(found.getScore()).isEqualTo((short) 72);
		assertThat(found.getCovered()).isEqualTo("[0, 2]");
		assertThat(found.getMissed()).isEqualTo("[1]");
		assertThat(found.getScoredAt()).isNotNull();
		assertThat(found.scored()).isTrue();
	}

	@Test
	@DisplayName("같은 세션에 같은 질문을 두 번 넣지 못한다 — 총점이 흔들리지 않게 하는 제약")
	void rejectsDuplicateQuestionInSession() {
		InterviewSession session = newSession(1);
		newAnswer(session, "첫 답변");
		entityManager.flush();

		// saveAndFlush 로 부르는 이유: entityManager.flush() 를 직접 부르면 Hibernate 예외가
		// 그대로 올라온다. Spring 의 예외 변환은 리포지토리 프록시 경계에서 일어나므로,
		// 운영 코드가 실제로 보게 될 예외를 고정하려면 리포지토리를 거쳐야 한다.
		InterviewAnswer duplicate = new InterviewAnswer(session, question, 1, "같은 질문에 두 번째 답변",
				10_000, 90, OffsetDateTime.now().plusDays(90));

		assertThatThrownBy(() -> answerRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("답하지 못한 문항은 transcript 가 null 이고 0점으로 센다")
	void treatsUnansweredAsZero() {
		InterviewSession session = newSession(2);
		// 공백만 말한 경우도 답하지 않은 것으로 본다.
		InterviewAnswer silent = newAnswer(session, "   ");

		entityManager.flush();
		entityManager.clear();

		InterviewAnswer found = answerRepository.findById(silent.getId()).orElseThrow();
		assertThat(found.getTranscript()).isNull();
		assertThat(found.answered()).isFalse();
		assertThat(found.scoreOrZero()).isZero();
	}

	@Test
	@DisplayName("재제출하면 이전 채점 결과가 함께 지워진다 — 답이 바뀌었는데 점수만 남으면 안 된다")
	void resubmitClearsPreviousScore() {
		InterviewSession session = newSession(1);
		InterviewAnswer answer = newAnswer(session, "첫 답변");
		answer.applyScore(40, "[0]", "[1,2]", "부족합니다.", OffsetDateTime.now());
		entityManager.flush();

		answer.resubmit("다시 말한 답변", 51_000, 90, OffsetDateTime.now().plusDays(90));
		entityManager.flush();
		entityManager.clear();

		InterviewAnswer found = answerRepository.findById(answer.getId()).orElseThrow();
		assertThat(found.getTranscript()).isEqualTo("다시 말한 답변");
		assertThat(found.getScore()).isNull();
		assertThat(found.getCovered()).isNull();
		assertThat(found.getFeedback()).isNull();
		assertThat(found.getScoredAt()).isNull();
		assertThat(found.getDurationMs()).isEqualTo(51_000);
	}

	@Test
	@DisplayName("TTL 이 지난 발화 원문만 지운다 — 점수와 피드백은 남는다")
	void forgetsExpiredTranscriptsOnly() {
		InterviewSession session = newSession(1);
		InterviewAnswer answer = answerRepository.save(new InterviewAnswer(session, question, 0,
				"오래된 발화", 30_000, 90, OffsetDateTime.now().minus(1, ChronoUnit.DAYS)));
		answer.applyScore(80, "[0,1]", "[2]", "잘 답했습니다.", OffsetDateTime.now());
		entityManager.flush();

		int forgotten = answerRepository.forgetExpiredTranscripts(OffsetDateTime.now());
		entityManager.clear();

		assertThat(forgotten).isEqualTo(1);
		InterviewAnswer found = answerRepository.findById(answer.getId()).orElseThrow();
		assertThat(found.getTranscript()).isNull();
		// 기록 전체를 지우면 "내 면접 기록" 기능이 죽는다.
		assertThat(found.getScore()).isEqualTo((short) 80);
		assertThat(found.getFeedback()).isEqualTo("잘 답했습니다.");
		assertThat(found.getCovered()).isEqualTo("[0, 1]");
	}

	@Test
	@DisplayName("만료되지 않은 발화는 건드리지 않는다")
	void keepsUnexpiredTranscripts() {
		InterviewSession session = newSession(1);
		InterviewAnswer answer = newAnswer(session, "아직 유효한 발화");
		entityManager.flush();

		int forgotten = answerRepository.forgetExpiredTranscripts(OffsetDateTime.now());
		entityManager.clear();

		assertThat(forgotten).isZero();
		assertThat(answerRepository.findById(answer.getId()).orElseThrow().getTranscript())
			.isEqualTo("아직 유효한 발화");
	}

	@Test
	@DisplayName("기록 목록은 소유자 것만, 최근순으로 — 남의 세션이 섞이면 안 된다")
	void listsOwnSessionsOnly() {
		newSession(5);
		sessionRepository.save(new InterviewSession("user:someone-else", posting, questionSet, 5));
		entityManager.flush();

		List<InterviewSession> mine = sessionRepository
			.findByOwner(OWNER, PageRequest.of(0, 20))
			.getContent();

		assertThat(mine).hasSize(1);
		assertThat(mine.getFirst().getOwnerKey()).isEqualTo(OWNER);
		// join fetch 된 공고가 프록시가 아니라 실제 값이어야 목록에서 바로 쓸 수 있다.
		assertThat(mine.getFirst().getJobPosting().getCompany()).isEqualTo("토스");
	}

	@Test
	@DisplayName("남의 세션은 id 를 알아도 열리지 않는다")
	void hidesOtherOwnersSession() {
		InterviewSession session = newSession(5);
		entityManager.flush();

		assertThat(sessionRepository.findByIdAndOwnerKey(session.getId(), OWNER)).isPresent();
		assertThat(sessionRepository.findByIdAndOwnerKey(session.getId(), "user:someone-else"))
			.isEmpty();
	}

	@Test
	@DisplayName("익명 세션을 계정으로 승계한다 — 제출 이력과 달리 충돌 처리가 필요 없다")
	void transfersOwnership() {
		sessionRepository.save(new InterviewSession("anon:sess-1", posting, questionSet, 5));
		// 같은 공고로 계정에도 이미 세션이 있다. jd_submission 이라면 유니크 제약에 걸릴 상황인데,
		// 세션은 같은 공고로 몇 번이든 연습할 수 있어 제약 자체가 없다.
		sessionRepository.save(new InterviewSession("user:target", posting, questionSet, 5));
		entityManager.flush();

		int moved = sessionRepository.transferOwnership("anon:sess-1", "user:target");

		assertThat(moved).isEqualTo(1);
		assertThat(sessionRepository.findByOwner("user:target", PageRequest.of(0, 20))
			.getTotalElements()).isEqualTo(2);
	}

	@Test
	@DisplayName("총점을 붙이면 세션이 닫힌다")
	void finishesSession() {
		InterviewSession session = newSession(2);
		session.recordAnswered();
		session.finish(64, OffsetDateTime.now());

		entityManager.flush();
		entityManager.clear();

		InterviewSession found = sessionRepository.findById(session.getId()).orElseThrow();
		assertThat(found.getTotalScore()).isEqualTo((short) 64);
		assertThat(found.getAnsweredCount()).isEqualTo((short) 1);
		assertThat(found.isFinished()).isTrue();
	}

	@Test
	@DisplayName("answered_count 는 question_count 를 넘지 못한다 — CHECK 제약에 닿기 전에 막는다")
	void guardsAnsweredCount() {
		InterviewSession session = newSession(1);
		session.recordAnswered();

		assertThatThrownBy(session::recordAnswered).isInstanceOf(IllegalStateException.class);
	}
}
