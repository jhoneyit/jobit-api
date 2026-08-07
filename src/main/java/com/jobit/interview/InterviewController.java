package com.jobit.interview;

import com.jobit.common.OwnerKey;
import com.jobit.jd.JobPosting;
import com.jobit.question.Question;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

	/** 한 번에 내려줄 수 있는 최대 줄 수 ({@code SubmissionController}와 같은 값). */
	private static final int MAX_SIZE = 100;

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

	/**
	 * {@code GET /api/interviews} — 내 면접 기록 목록.
	 *
	 * <p>제출 이력과 달리 <b>집계를 붙이지 않는다</b> — 총점·답변 수가 이미 세션 행에 있다.
	 */
	@GetMapping
	public SessionPage list(@RequestHeader("X-Owner-Key") String ownerKey,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {

		Page<InterviewSession> found = interviewService.list(OwnerKey.requireValid(ownerKey),
				PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_SIZE)));

		return new SessionPage(found.getContent().stream().map(SessionSummary::of).toList(),
				found.getNumber(), found.getSize(), found.getTotalElements(),
				found.getTotalPages());
	}

	/** {@code GET /api/interviews/{sessionId}} — 문항별 점수와 뼈대 대조. */
	@GetMapping("/{sessionId}")
	public SessionDetailResponse detail(@PathVariable UUID sessionId,
			@RequestHeader("X-Owner-Key") String ownerKey) {

		return SessionDetailResponse
			.of(interviewService.detail(sessionId, OwnerKey.requireValid(ownerKey)));
	}

	/**
	 * {@code DELETE /api/interviews/{sessionId}} — 기록 삭제.
	 *
	 * <p>답변은 함께 지워지고 <b>질문·공고는 남는다</b> — 둘 다 공유 자산이다.
	 */
	@DeleteMapping("/{sessionId}")
	public ResponseEntity<Void> delete(@PathVariable UUID sessionId,
			@RequestHeader("X-Owner-Key") String ownerKey) {

		interviewService.delete(sessionId, OwnerKey.requireValid(ownerKey));
		return ResponseEntity.noContent().build();
	}

	/**
	 * {@code POST /api/interviews/claim} — 익명 연습 기록을 계정으로 승계한다.
	 *
	 * <p>제출 이력({@code /api/submissions/claim})과 같은 규약이고 <b>방향도 같이 강제한다</b> —
	 * 반대 방향을 허용하면 계정 기록을 익명 키로 빼내는 경로가 생긴다.
	 *
	 * <p>엔드포인트를 따로 두는 이유: 자원이 다르다. 프론트는 로그인 직후 제출 이력·프로필과
	 * 함께 이것도 부른다 (이미 두 개를 따로 부르고 있다).
	 */
	@PostMapping("/claim")
	public ClaimResult claim(@RequestHeader("X-Owner-Key") String toOwnerKey,
			@RequestBody ClaimRequest request) {

		OwnerKey.requireValid(toOwnerKey);
		OwnerKey.requireValid(request.fromOwnerKey());

		if (!toOwnerKey.startsWith(OwnerKey.USER_PREFIX)) {
			throw new IllegalArgumentException("claim target must be a user key");
		}
		if (!OwnerKey.isAnonymous(request.fromOwnerKey())) {
			throw new IllegalArgumentException("claim source must be an anonymous key");
		}

		return new ClaimResult(
				interviewService.transferOwnership(request.fromOwnerKey(), toOwnerKey));
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

	public record SessionPage(List<SessionSummary> items, int page, int size, long totalElements,
			int totalPages) {
	}

	/**
	 * 기록 목록 한 줄.
	 *
	 * @param totalScore 종료 전이면 {@code null}. 0으로 채우지 않는다 — "아직 안 끝냈다"와
	 *                   "끝냈는데 0점"은 화면에서 다르게 보여야 한다
	 * @param finishedAt {@code null}이면 중간에 이탈했거나 진행 중이다
	 */
	public record SessionSummary(UUID sessionId, UUID jobPostingId, String company, String title,
			Integer totalScore, int answeredCount, int questionCount, OffsetDateTime startedAt,
			OffsetDateTime finishedAt) {

		static SessionSummary of(InterviewSession session) {
			JobPosting posting = session.getJobPosting();
			return new SessionSummary(session.getId(), posting.getId(), posting.getCompany(),
					posting.getTitle(),
					session.getTotalScore() == null ? null : (int) session.getTotalScore(),
					session.getAnsweredCount(), session.getQuestionCount(), session.getStartedAt(),
					session.getFinishedAt());
		}
	}

	/**
	 * @param questions 이 세션에 출제된 문항. <b>답변 뼈대가 없다</b> — 뼈대는 채점된
	 *                  {@code answers} 안에만 실린다. 연습 화면이 새로고침 뒤 이어서 하려면
	 *                  이 목록이 필요하다
	 */
	public record SessionDetailResponse(UUID sessionId, UUID jobPostingId, String company,
			String title, Integer totalScore, int answeredCount, int questionCount,
			OffsetDateTime startedAt, OffsetDateTime finishedAt, List<QuestionView> questions,
			List<AnsweredQuestionView> answers) {

		static SessionDetailResponse of(InterviewService.SessionDetail detail) {
			InterviewSession session = detail.session();
			JobPosting posting = session.getJobPosting();
			return new SessionDetailResponse(session.getId(), posting.getId(),
					posting.getCompany(), posting.getTitle(),
					session.getTotalScore() == null ? null : (int) session.getTotalScore(),
					session.getAnsweredCount(), session.getQuestionCount(), session.getStartedAt(),
					session.getFinishedAt(),
					detail.questions().stream().map(QuestionView::of).toList(),
					detail.answers().stream().map(AnsweredQuestionView::of).toList());
		}
	}

	/**
	 * 상세 화면의 한 줄.
	 *
	 * @param transcript 발화 원문. <b>{@code null}일 수 있다</b> — 답하지 못했거나, TTL이 지나
	 *                   원문만 지워진 경우다. 후자는 {@code answered}가 {@code true}인데
	 *                   {@code transcript}가 없는 상태로 나타난다
	 * @param score 채점 전이면 {@code null}
	 */
	public record AnsweredQuestionView(UUID questionId, String questionText,
			Question.Category category, short difficulty, boolean answered, String transcript,
			Integer score, List<String> outline, List<Integer> covered, List<Integer> missed,
			String feedback, int durationMs, int timeLimitSec) {

		static AnsweredQuestionView of(InterviewService.AnsweredQuestion answered) {
			InterviewAnswer answer = answered.answer();
			Question question = answer.getQuestion();
			return new AnsweredQuestionView(question.getId(), question.getText(),
					question.getCategory(), question.getDifficulty(), answer.answered(),
					answer.getTranscript(),
					answer.getScore() == null ? null : (int) answer.getScore(),
					answered.outline(), answered.covered(), answered.missed(),
					answer.getFeedback(), answer.getDurationMs(), answer.getTimeLimitSec());
		}
	}

	/** {@code fromOwnerKey}의 검증은 {@code OwnerKey.requireValid}가 한다 — null·공백도 거기서 걸린다. */
	public record ClaimRequest(String fromOwnerKey) {
	}

	/** @param moved 옮겨진 세션 수 */
	public record ClaimResult(int moved) {
	}
}
