package com.jobit.interview;

import com.jobit.common.OwnerKey;
import com.jobit.question.Question;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 면접 연습 세션 엔드포인트 (docs/interview-practice-design.md §8).
 *
 * <p><b>오디오를 받지 않는다.</b> STT 는 브라우저(Web Speech API)가 하고 여기로는 텍스트만
 * 온다. 그래서 멀티파트도, 업로드 상한도, 오디오 저장 경로도 없다 — 이 기능의 개인정보 대책이
 * 사실상 이 한 줄이다.
 *
 * <p>컨트롤러는 변환과 검증만 한다. 지출 방어·소유자 확인·채점은 {@link InterviewService}가 갖는다.
 */
@RestController
@RequestMapping(path = "/api/interviews", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class InterviewController {

	private final InterviewService interviewService;

	/**
	 * {@code POST /api/interviews} — 세션 시작.
	 *
	 * <p>질문이 아직 없는 공고면 {@code 400}이다. 채점 기준({@code answer_outline})이 질문 생성의
	 * 산물이라, 질문이 없으면 채점할 기준도 없다.
	 */
	@PostMapping
	public StartResponse start(@RequestHeader("X-Owner-Key") String ownerKey,
			@Valid @RequestBody StartRequest request) {

		InterviewService.StartedSession started = interviewService
			.start(OwnerKey.requireValid(ownerKey), request.jobPostingId());

		return StartResponse.of(started);
	}

	/**
	 * {@code POST /api/interviews/{sessionId}/answers} — 답변 제출 + 즉시 채점.
	 *
	 * <p><b>{@code transcript}가 비어 있는 것은 오류가 아니다.</b> 제한 시간 안에 한마디도 못 한
	 * 경우가 정상 경로이고 그 자체가 결과다 — 0점으로 기록되며 LLM 을 부르지 않는다.
	 */
	@PostMapping("/{sessionId}/answers")
	public AnswerResponse submitAnswer(@PathVariable UUID sessionId,
			@RequestHeader("X-Owner-Key") String ownerKey,
			@Valid @RequestBody AnswerRequest request) {

		InterviewService.ScoredAnswer scored = interviewService.submitAnswer(sessionId,
				OwnerKey.requireValid(ownerKey), request.questionId(), request.transcript(),
				request.durationMs());

		return AnswerResponse.of(scored);
	}

	/**
	 * {@code POST /api/interviews/{sessionId}/finish} — 종료 · 총점 확정.
	 *
	 * <p>이미 닫힌 세션에 다시 불러도 같은 결과를 준다. 마지막 문항 제출과 종료가 겹치거나
	 * 사용자가 새로고침하는 것은 정상 경로다.
	 */
	@PostMapping("/{sessionId}/finish")
	public FinishResponse finish(@PathVariable UUID sessionId,
			@RequestHeader("X-Owner-Key") String ownerKey) {

		return FinishResponse.of(interviewService.finish(sessionId, OwnerKey.requireValid(ownerKey)));
	}

	public record StartRequest(@NotNull(message = "공고를 선택해 주세요.") UUID jobPostingId) {
	}

	/**
	 * @param transcript 브라우저 STT 결과. 시간 내 답하지 못했으면 null 또는 빈 문자열.
	 *                   상한은 <b>폭주 방어</b>다 — 제한 시간이 90초인데 그 안에 말할 수 있는
	 *                   양을 한참 넘는 값이 오면 사람이 말한 것이 아니다
	 */
	public record AnswerRequest(@NotNull(message = "질문을 선택해 주세요.") UUID questionId,

			@Size(max = 10_000, message = "답변이 너무 깁니다.") String transcript,

			@PositiveOrZero(message = "답변 길이가 올바르지 않습니다.") int durationMs) {
	}

	/**
	 * 세션 시작 응답.
	 *
	 * <p><b>답변 뼈대가 없다.</b> 보고 답하면 연습이 아니다 — 뼈대는 채점 응답에서 처음 나온다.
	 */
	public record StartResponse(UUID sessionId, UUID jobPostingId, int questionCount,
			List<QuestionView> questions) {

		static StartResponse of(InterviewService.StartedSession started) {
			InterviewSession session = started.session();
			return new StartResponse(session.getId(), session.getJobPosting().getId(),
					session.getQuestionCount(),
					started.questions().stream().map(QuestionView::of).toList());
		}
	}

	public record QuestionView(UUID questionId, String text, Question.Category category,
			short difficulty, int timeLimitSec) {

		static QuestionView of(InterviewService.QuestionPrompt prompt) {
			return new QuestionView(prompt.questionId(), prompt.text(), prompt.category(),
					prompt.difficulty(), prompt.timeLimitSec());
		}
	}

	/**
	 * 채점 응답.
	 *
	 * @param outline 답변 뼈대. <b>여기서 처음 공개된다</b> — {@code covered}/{@code missed}가
	 *                이 목록의 인덱스를 가리키므로 함께 내려야 화면이 짝을 맞출 수 있다
	 * @param answered 답했는가. {@code false}면 시간 내에 말하지 못한 것이고, 점수 0은 채점
	 *                 결과가 아니라 그 사실의 표현이다
	 */
	public record AnswerResponse(UUID questionId, boolean answered, int score,
			List<String> outline, List<Integer> covered, List<Integer> missed, String feedback,
			int answeredCount, int questionCount) {

		static AnswerResponse of(InterviewService.ScoredAnswer scored) {
			InterviewAnswer answer = scored.answer();
			InterviewSession session = answer.getSession();
			return new AnswerResponse(answer.getQuestion().getId(), answer.answered(),
					scored.score().score(), scored.outline(), scored.score().covered(),
					scored.score().missed(), scored.score().feedback(),
					session.getAnsweredCount(), session.getQuestionCount());
		}
	}

	/** @param totalScore 출제된 <b>전</b> 문항의 평균. 미답변은 0점으로 센다 */
	public record FinishResponse(UUID sessionId, int totalScore, int answeredCount,
			int questionCount, OffsetDateTime finishedAt) {

		static FinishResponse of(InterviewSession session) {
			return new FinishResponse(session.getId(),
					session.getTotalScore() == null ? 0 : session.getTotalScore(),
					session.getAnsweredCount(), session.getQuestionCount(),
					session.getFinishedAt());
		}
	}
}
