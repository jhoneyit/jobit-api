package com.jobit.interview;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobit.common.ApiExceptionHandler;
import com.jobit.common.NotFoundException;
import com.jobit.jd.JobPosting;
import com.jobit.question.Question;
import com.jobit.question.QuestionSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code /api/interviews}의 계약 (docs/api.md).
 *
 * <p>여기서 고정하는 것 중 가장 중요한 둘: <b>세션 시작 응답에 답변 뼈대가 없다</b>(보고 답하면
 * 연습이 아니다)와 <b>채점 응답에는 있다</b>(covered/missed 가 그 인덱스를 가리킨다).
 */
@WebMvcTest(InterviewController.class)
@Import(ApiExceptionHandler.class)
class InterviewControllerTest {

	private static final String OWNER = "user:abc123";

	private static final UUID SESSION_ID = UUID.randomUUID();

	private static final UUID JOB_POSTING_ID = UUID.randomUUID();

	private static final UUID QUESTION_ID = UUID.randomUUID();

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private InterviewService interviewService;

	private InterviewSession session;

	@BeforeEach
	void setUp() {
		JobPosting posting = new JobPosting("hash", "본문", null, "토스", "백엔드", "{}");
		ReflectionTestUtils.setField(posting, "id", JOB_POSTING_ID);
		QuestionSet questionSet = new QuestionSet(posting, "v1", "claude-opus-5");
		ReflectionTestUtils.setField(questionSet, "id", UUID.randomUUID());

		session = new InterviewSession(OWNER, posting, questionSet, 3);
		ReflectionTestUtils.setField(session, "id", SESSION_ID);
	}

	private void givenStarted() {
		given(interviewService.start(eq(OWNER), eq(JOB_POSTING_ID)))
			.willReturn(new InterviewService.StartedSession(session,
					List.of(new InterviewService.QuestionPrompt(QUESTION_ID, "트랜잭션 격리 수준은?",
							Question.Category.CS, (short) 3, 90))));
	}

	private InterviewService.ScoredAnswer scoredAnswer(String transcript, int score) {
		JobPosting posting = session.getJobPosting();
		QuestionSet questionSet = session.getQuestionSet();
		Question question = new Question(questionSet, null, "트랜잭션 격리 수준은?",
				Question.Category.CS, (short) 3, "[]", "[\"뼈대 A\",\"뼈대 B\"]", 0);
		ReflectionTestUtils.setField(question, "id", QUESTION_ID);
		ReflectionTestUtils.setField(posting, "id", JOB_POSTING_ID);

		InterviewAnswer answer = new InterviewAnswer(session, question, 0, transcript, 30_000, 90,
				OffsetDateTime.now().plusDays(90));
		ReflectionTestUtils.setField(session, "answeredCount", (short) 1);

		return new InterviewService.ScoredAnswer(answer, List.of("뼈대 A", "뼈대 B"),
				new AnswerScorer.Score(score, List.of(0), List.of(1), "핵심은 짚었습니다."));
	}

	// ── 세션 시작 ────────────────────────────────────────────────

	@Test
	@DisplayName("세션을 시작하면 출제 질문과 제한 시간을 준다")
	void startsSession() throws Exception {
		givenStarted();

		mockMvc
			.perform(post("/api/interviews").header("X-Owner-Key", OWNER)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"jobPostingId\":\"" + JOB_POSTING_ID + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
			.andExpect(jsonPath("$.questionCount").value(3))
			.andExpect(jsonPath("$.questions[0].questionId").value(QUESTION_ID.toString()))
			.andExpect(jsonPath("$.questions[0].timeLimitSec").value(90));
	}

	@Test
	@DisplayName("시작 응답에 답변 뼈대가 없다 — 보고 답하면 연습이 아니다")
	void hidesOutlineWhenStarting() throws Exception {
		givenStarted();

		mockMvc
			.perform(post("/api/interviews").header("X-Owner-Key", OWNER)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"jobPostingId\":\"" + JOB_POSTING_ID + "\"}"))
			.andExpect(jsonPath("$.questions[0].outline").doesNotExist())
			.andExpect(jsonPath("$.questions[0].answerOutline").doesNotExist());
	}

	@Test
	@DisplayName("질문이 없는 공고면 400 이고, 문구가 다음 행동을 가리킨다")
	void tellsUserToGenerateQuestionsFirst() throws Exception {
		given(interviewService.start(anyString(), any()))
			.willThrow(new InterviewService.QuestionsNotReadyException(JOB_POSTING_ID));

		mockMvc
			.perform(post("/api/interviews").header("X-Owner-Key", OWNER)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"jobPostingId\":\"" + JOB_POSTING_ID + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("이 공고의 예상 질문을 먼저 만들어 주세요."));
	}

	@Test
	@DisplayName("일별 세션 상한은 429 — 내일 다시 오라고 말한다")
	void mapsDailySessionLimitTo429() throws Exception {
		given(interviewService.start(anyString(), any()))
			.willThrow(new InterviewService.DailySessionLimitExceededException(6));

		mockMvc
			.perform(post("/api/interviews").header("X-Owner-Key", OWNER)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"jobPostingId\":\"" + JOB_POSTING_ID + "\"}"))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.error").value("오늘 면접 연습 횟수를 모두 사용했습니다. 내일 다시 시도해 주세요."));
	}

	@Test
	@DisplayName("X-Owner-Key 가 없으면 400")
	void requiresOwnerKeyToStart() throws Exception {
		mockMvc
			.perform(post("/api/interviews").contentType(MediaType.APPLICATION_JSON)
				.content("{\"jobPostingId\":\"" + JOB_POSTING_ID + "\"}"))
			.andExpect(status().isBadRequest());

		then(interviewService).should(never()).start(anyString(), any());
	}

	@Test
	@DisplayName("jobPostingId 가 없으면 400 — 검증 문구를 그대로 보여준다")
	void requiresJobPostingId() throws Exception {
		mockMvc.perform(post("/api/interviews").header("X-Owner-Key", OWNER)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("공고를 선택해 주세요."));
	}

	// ── 답변 제출 ────────────────────────────────────────────────

	@Test
	@DisplayName("채점 응답에는 뼈대가 실린다 — covered/missed 가 그 인덱스를 가리킨다")
	void revealsOutlineWhenScoring() throws Exception {
		given(interviewService.submitAnswer(eq(SESSION_ID), eq(OWNER), eq(QUESTION_ID), anyString(),
				anyInt())).willReturn(scoredAnswer("격리 수준은...", 62));

		mockMvc
			.perform(post("/api/interviews/" + SESSION_ID + "/answers").header("X-Owner-Key", OWNER)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"questionId\":\"" + QUESTION_ID
						+ "\",\"transcript\":\"격리 수준은...\",\"durationMs\":30000}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.score").value(62))
			.andExpect(jsonPath("$.answered").value(true))
			.andExpect(jsonPath("$.outline[0]").value("뼈대 A"))
			.andExpect(jsonPath("$.covered[0]").value(0))
			.andExpect(jsonPath("$.missed[0]").value(1))
			.andExpect(jsonPath("$.feedback").value("핵심은 짚었습니다."))
			.andExpect(jsonPath("$.answeredCount").value(1))
			.andExpect(jsonPath("$.questionCount").value(3));
	}

	@Test
	@DisplayName("빈 transcript 는 오류가 아니다 — 시간 내 못 답한 것도 결과다")
	void acceptsEmptyTranscript() throws Exception {
		given(interviewService.submitAnswer(eq(SESSION_ID), eq(OWNER), eq(QUESTION_ID), any(),
				anyInt())).willReturn(scoredAnswer(null, 0));

		mockMvc
			.perform(post("/api/interviews/" + SESSION_ID + "/answers").header("X-Owner-Key", OWNER)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"questionId\":\"" + QUESTION_ID
						+ "\",\"transcript\":\"\",\"durationMs\":90000}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.answered").value(false))
			.andExpect(jsonPath("$.score").value(0));
	}

	@Test
	@DisplayName("남의 세션에 답변하면 404 — 존재 여부도 알려주지 않는다")
	void hidesOtherOwnersSession() throws Exception {
		given(interviewService.submitAnswer(any(), anyString(), any(), any(), anyInt()))
			.willThrow(new NotFoundException("interview session not found"));

		mockMvc
			.perform(post("/api/interviews/" + SESSION_ID + "/answers").header("X-Owner-Key", OWNER)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"questionId\":\"" + QUESTION_ID
						+ "\",\"transcript\":\"답변\",\"durationMs\":1000}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("찾을 수 없습니다."));
	}

	@Test
	@DisplayName("지나치게 긴 답변은 400 — 90초 안에 말할 수 있는 양이 아니다")
	void rejectsOversizedTranscript() throws Exception {
		mockMvc
			.perform(post("/api/interviews/" + SESSION_ID + "/answers").header("X-Owner-Key", OWNER)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"questionId\":\"" + QUESTION_ID + "\",\"transcript\":\""
						+ "가".repeat(10_001) + "\",\"durationMs\":1000}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("답변이 너무 깁니다."));

		then(interviewService).should(never()).submitAnswer(any(), anyString(), any(), any(),
				anyInt());
	}

	// ── 종료 ─────────────────────────────────────────────────────

	@Test
	@DisplayName("종료하면 총점을 준다")
	void finishesSession() throws Exception {
		ReflectionTestUtils.setField(session, "answeredCount", (short) 2);
		session.finish(46, OffsetDateTime.parse("2026-08-07T10:22:11Z"));
		given(interviewService.finish(SESSION_ID, OWNER)).willReturn(session);

		mockMvc
			.perform(post("/api/interviews/" + SESSION_ID + "/finish").header("X-Owner-Key", OWNER))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalScore").value(46))
			.andExpect(jsonPath("$.answeredCount").value(2))
			.andExpect(jsonPath("$.questionCount").value(3))
			.andExpect(jsonPath("$.finishedAt").exists());
	}

	@Test
	@DisplayName("UUID 가 아닌 세션 id 는 400")
	void rejectsMalformedSessionId() throws Exception {
		mockMvc.perform(post("/api/interviews/not-a-uuid/finish").header("X-Owner-Key", OWNER))
			.andExpect(status().isBadRequest());
	}
}
