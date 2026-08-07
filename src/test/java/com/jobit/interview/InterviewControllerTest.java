package com.jobit.interview;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

	// ── 기록 조회 ────────────────────────────────────────────────

	@Test
	@DisplayName("기록 목록은 공고와 점수를 함께 준다")
	void listsRecords() throws Exception {
		ReflectionTestUtils.setField(session, "answeredCount", (short) 4);
		session.finish(72, OffsetDateTime.parse("2026-08-07T10:22:11Z"));
		given(interviewService.list(eq(OWNER), any(Pageable.class)))
			.willReturn(new PageImpl<>(List.of(session), PageRequest.of(0, 20), 1));

		mockMvc.perform(get("/api/interviews").header("X-Owner-Key", OWNER))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].sessionId").value(SESSION_ID.toString()))
			.andExpect(jsonPath("$.items[0].company").value("토스"))
			.andExpect(jsonPath("$.items[0].totalScore").value(72))
			.andExpect(jsonPath("$.items[0].answeredCount").value(4))
			.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	@DisplayName("끝나지 않은 세션은 totalScore 가 null — 0점과 구분해야 한다")
	void leavesTotalScoreNullWhileUnfinished() throws Exception {
		given(interviewService.list(eq(OWNER), any(Pageable.class)))
			.willReturn(new PageImpl<>(List.of(session), PageRequest.of(0, 20), 1));

		mockMvc.perform(get("/api/interviews").header("X-Owner-Key", OWNER))
			.andExpect(jsonPath("$.items[0].totalScore").doesNotExist())
			.andExpect(jsonPath("$.items[0].finishedAt").doesNotExist());
	}

	@Test
	@DisplayName("목록에 X-Owner-Key 가 없으면 400 — 빈 목록으로 얼버무리지 않는다")
	void requiresOwnerKeyToList() throws Exception {
		mockMvc.perform(get("/api/interviews")).andExpect(status().isBadRequest());

		then(interviewService).should(never()).list(anyString(), any());
	}

	@Test
	@DisplayName("size 는 1~100 으로 잘린다")
	void clampsPageSize() throws Exception {
		given(interviewService.list(eq(OWNER), any(Pageable.class)))
			.willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

		mockMvc.perform(get("/api/interviews").header("X-Owner-Key", OWNER).param("size", "5000"))
			.andExpect(status().isOk());

		then(interviewService).should().list(OWNER, PageRequest.of(0, 100));
	}

	@Test
	@DisplayName("상세는 문항별 점수와 뼈대 대조를 준다")
	void showsDetail() throws Exception {
		InterviewService.ScoredAnswer scored = scoredAnswer("격리 수준은...", 62);
		scored.answer().applyScore(62, "[0]", "[1]", "핵심은 짚었습니다.", OffsetDateTime.now());
		given(interviewService.detail(SESSION_ID, OWNER))
			.willReturn(new InterviewService.SessionDetail(session,
					List.of(new InterviewService.QuestionPrompt(QUESTION_ID, "트랜잭션 격리 수준은?",
							Question.Category.CS, (short) 3, 90)),
					List.of(new InterviewService.AnsweredQuestion(scored.answer(),
							List.of("뼈대 A", "뼈대 B"), List.of(0), List.of(1)))));

		mockMvc.perform(get("/api/interviews/" + SESSION_ID).header("X-Owner-Key", OWNER))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
			// 이어서 하기용 문항 목록. 여기에는 뼈대가 없어야 한다.
			.andExpect(jsonPath("$.questions[0].questionId").value(QUESTION_ID.toString()))
			.andExpect(jsonPath("$.questions[0].outline").doesNotExist())
			.andExpect(jsonPath("$.answers[0].questionText").value("트랜잭션 격리 수준은?"))
			.andExpect(jsonPath("$.answers[0].answered").value(true))
			.andExpect(jsonPath("$.answers[0].transcript").value("격리 수준은..."))
			.andExpect(jsonPath("$.answers[0].score").value(62))
			.andExpect(jsonPath("$.answers[0].outline[1]").value("뼈대 B"))
			.andExpect(jsonPath("$.answers[0].covered[0]").value(0))
			.andExpect(jsonPath("$.answers[0].missed[0]").value(1))
			.andExpect(jsonPath("$.answers[0].timeLimitSec").value(90));
	}

	@Test
	@DisplayName("TTL 이 지나 원문이 지워져도 점수는 남는다 — answered=true 인데 transcript 가 없다")
	void keepsScoreAfterTranscriptExpiry() throws Exception {
		InterviewService.ScoredAnswer scored = scoredAnswer("지워질 발화", 80);
		scored.answer().applyScore(80, "[0,1]", "[]", "잘 답했습니다.", OffsetDateTime.now());
		scored.answer().forgetTranscript();
		given(interviewService.detail(SESSION_ID, OWNER))
			.willReturn(new InterviewService.SessionDetail(session, List.of(),
					List.of(new InterviewService.AnsweredQuestion(scored.answer(),
							List.of("뼈대 A", "뼈대 B"), List.of(0, 1), List.of()))));

		mockMvc.perform(get("/api/interviews/" + SESSION_ID).header("X-Owner-Key", OWNER))
			.andExpect(jsonPath("$.answers[0].transcript").doesNotExist())
			.andExpect(jsonPath("$.answers[0].score").value(80))
			.andExpect(jsonPath("$.answers[0].feedback").value("잘 답했습니다."));
	}

	@Test
	@DisplayName("남의 기록 상세는 404")
	void hidesOtherOwnersDetail() throws Exception {
		given(interviewService.detail(any(), anyString()))
			.willThrow(new NotFoundException("interview session not found"));

		mockMvc.perform(get("/api/interviews/" + SESSION_ID).header("X-Owner-Key", OWNER))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("찾을 수 없습니다."));
	}

	@Test
	@DisplayName("삭제는 204 이고 본문이 없다")
	void deletesRecord() throws Exception {
		mockMvc.perform(delete("/api/interviews/" + SESSION_ID).header("X-Owner-Key", OWNER))
			.andExpect(status().isNoContent());

		then(interviewService).should().delete(SESSION_ID, OWNER);
	}

	@Test
	@DisplayName("남의 기록 삭제는 404")
	void hidesOtherOwnersRecordOnDelete() throws Exception {
		willThrow(new NotFoundException("not found")).given(interviewService)
			.delete(eq(SESSION_ID), anyString());

		mockMvc.perform(delete("/api/interviews/" + SESSION_ID).header("X-Owner-Key", OWNER))
			.andExpect(status().isNotFound());
	}

	// ── 승계 ─────────────────────────────────────────────────────

	@Test
	@DisplayName("익명 기록을 계정으로 옮긴다")
	void claimsAnonymousRecords() throws Exception {
		given(interviewService.transferOwnership("anon:sess-1", OWNER)).willReturn(2);

		mockMvc
			.perform(post("/api/interviews/claim").header("X-Owner-Key", OWNER)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fromOwnerKey\":\"anon:sess-1\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.moved").value(2));
	}

	@Test
	@DisplayName("계정 → 익명 방향은 막는다 — 계정 기록을 익명 키로 빼내는 경로가 된다")
	void rejectsReverseClaim() throws Exception {
		mockMvc
			.perform(post("/api/interviews/claim").header("X-Owner-Key", "anon:sess-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fromOwnerKey\":\"" + OWNER + "\"}"))
			.andExpect(status().isBadRequest());

		then(interviewService).should(never()).transferOwnership(anyString(), anyString());
	}

	@Test
	@DisplayName("출처가 익명이 아니면 막는다")
	void rejectsNonAnonymousSource() throws Exception {
		mockMvc
			.perform(post("/api/interviews/claim").header("X-Owner-Key", OWNER)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fromOwnerKey\":\"user:other\"}"))
			.andExpect(status().isBadRequest());

		then(interviewService).should(never()).transferOwnership(anyString(), anyString());
	}
}
