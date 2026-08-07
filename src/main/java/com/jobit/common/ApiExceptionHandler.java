package com.jobit.common;

import com.jobit.interview.AnswerScorerFallbackConfig.AnswerScorerNotConfiguredException;
import com.jobit.interview.InterviewService;
import com.jobit.jd.JdParserFallbackConfig.JdParserNotConfiguredException;
import com.jobit.llm.LlmException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import com.jobit.llm.LlmGuard;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * API 에러 응답 (docs/api.md 공통 규약).
 *
 * <p>형태는 {@code {"error": "<사용자에게 보여줄 한국어 문장>"}} 하나로 통일한다. 프론트가 그대로
 * 화면에 띄우므로 <b>내부 예외 메시지나 스택을 담지 않는다</b> — 그건 로그로만 남긴다.
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, String>> handleValidation(
			MethodArgumentNotValidException ex) {
		// 검증 메시지는 사용자에게 보여줄 문구로 작성해 두었다 (JdParseRequest 참고).
		String message = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> error.getDefaultMessage())
			.findFirst()
			.orElse("입력값을 확인해 주세요.");
		return body(HttpStatus.BAD_REQUEST, message);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, String>> handleUnreadable(
			HttpMessageNotReadableException ex) {
		log.debug("잘못된 요청 본문", ex);
		return body(HttpStatus.BAD_REQUEST, "잘못된 요청 형식입니다.");
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
		log.debug("잘못된 인자", ex);
		return body(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다.");
	}

	/**
	 * 필수 헤더 누락 — 지금은 {@code X-Owner-Key}가 유일하다.
	 *
	 * <p>기본 동작은 500이다. 헤더를 빠뜨린 것은 호출자의 실수이므로 400이어야 하고,
	 * 그래야 프론트가 "서버가 죽었다"와 구분할 수 있다 (docs/api.md 상태 코드 표).
	 */
	@ExceptionHandler(MissingRequestHeaderException.class)
	public ResponseEntity<Map<String, String>> handleMissingHeader(
			MissingRequestHeaderException ex) {
		log.debug("필수 헤더 누락: {}", ex.getHeaderName());
		return body(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다.");
	}

	/** 경로 변수 타입 불일치 (UUID 자리에 아무 문자열). 이것도 기본이 500이라 내려 준다. */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<Map<String, String>> handleTypeMismatch(
			MethodArgumentTypeMismatchException ex) {
		log.debug("경로/파라미터 타입 불일치: {}", ex.getName());
		return body(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다.");
	}

	/**
	 * 없는 자원, 또는 남의 자원. 둘을 구분하지 않는다 ({@link NotFoundException} 참고).
	 *
	 * <p>메시지를 예외에서 꺼내지 않는다 — "submission not found: &lt;uuid&gt;" 같은 내부 문구가
	 * 그대로 화면에 뜬다. 로그에만 남긴다.
	 */
	@ExceptionHandler(NotFoundException.class)
	public ResponseEntity<Map<String, String>> handleNotFound(NotFoundException ex) {
		log.debug("자원 없음", ex);
		return body(HttpStatus.NOT_FOUND, "찾을 수 없습니다.");
	}

	/**
	 * LLM 구현이 등록되지 않은 상태. 사용자 잘못이 아니므로 5xx다 — 재시도해도 소용없다는 것을
	 * 운영자가 로그에서 바로 알 수 있어야 한다.
	 */
	@ExceptionHandler(JdParserNotConfiguredException.class)
	public ResponseEntity<Map<String, String>> handleParserMissing(
			JdParserNotConfiguredException ex) {
		log.error("JdParser 구현이 없습니다. ANTHROPIC_API_KEY 설정을 확인하세요.", ex);
		return body(HttpStatus.INTERNAL_SERVER_ERROR, "공고 분석 기능이 아직 설정되지 않았습니다.");
	}

	/** 채점 구현이 없는 상태. 위와 같은 이유로 5xx다 — 재시도해도 소용없다. */
	@ExceptionHandler(AnswerScorerNotConfiguredException.class)
	public ResponseEntity<Map<String, String>> handleScorerMissing(
			AnswerScorerNotConfiguredException ex) {
		log.error("AnswerScorer 구현이 없습니다. ANTHROPIC_API_KEY 설정을 확인하세요.", ex);
		return body(HttpStatus.INTERNAL_SERVER_ERROR, "답변 채점 기능이 아직 설정되지 않았습니다.");
	}

	/**
	 * 소유자별 한도 초과. {@code Retry-After} 를 함께 주어 클라이언트가 언제 다시 시도할지
	 * 추측하지 않게 한다.
	 *
	 * <p>이 응답만 {@code message} 키를 쓰고 있었다. 규약은 {@code error} 하나다 —
	 * 키가 둘이면 프론트가 오류 문구를 꺼낼 때마다 어느 쪽인지 따져야 한다.
	 * ({@code QuestionController} 의 SSE {@code error} 이벤트는 별개 계약이라 {@code message} 다.)
	 */
	@ExceptionHandler(LlmGuard.RateLimitExceededException.class)
	public ResponseEntity<Map<String, String>> rateLimited(
			LlmGuard.RateLimitExceededException ex) {
		long minutes = Math.max(1, ex.getRetryAfterSeconds() / 60);
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
			.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
			.body(Map.of("error",
					"요청 한도를 초과했습니다. %d분 뒤에 다시 시도해 주세요.".formatted(minutes)));
	}

	/**
	 * 질문이 아직 없는 공고로 면접 연습을 시작하려 했다.
	 *
	 * <p>사용자가 할 일이 분명하므로(질문을 먼저 만든다) 문구가 다음 행동을 가리켜야 한다.
	 * "요청 값이 올바르지 않습니다"로 뭉뚱그리면 무엇을 해야 할지 알 수 없다.
	 */
	@ExceptionHandler(InterviewService.QuestionsNotReadyException.class)
	public ResponseEntity<Map<String, String>> questionsNotReady(
			InterviewService.QuestionsNotReadyException ex) {
		log.debug("질문이 없는 공고로 면접 연습 시도", ex);
		return body(HttpStatus.BAD_REQUEST, "이 공고의 예상 질문을 먼저 만들어 주세요.");
	}

	/**
	 * 면접 연습 일별 세션 상한.
	 *
	 * <p>{@code Retry-After} 를 주지 않는다 — 창이 자정에 열리므로 "몇 초 뒤"가 사용자에게
	 * 쓸모 있는 정보가 아니고, 문구로 "내일"이라고 말하는 편이 명확하다.
	 */
	@ExceptionHandler(InterviewService.DailySessionLimitExceededException.class)
	public ResponseEntity<Map<String, String>> dailySessionLimit(
			InterviewService.DailySessionLimitExceededException ex) {
		log.info("면접 연습 일별 상한 도달: {}", ex.getMessage());
		return body(HttpStatus.TOO_MANY_REQUESTS, "오늘 면접 연습 횟수를 모두 사용했습니다. 내일 다시 시도해 주세요.");
	}

	/** 전역 일일 상한. 사용자 잘못이 아니므로 문구가 다르다. */
	@ExceptionHandler(LlmGuard.DailyBudgetExceededException.class)
	public ResponseEntity<Map<String, String>> budgetExceeded(
			LlmGuard.DailyBudgetExceededException ex) {
		return body(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
	}

	@ExceptionHandler(LlmException.class)
	public ResponseEntity<Map<String, String>> handleLlm(LlmException ex) {
		HttpStatus status = switch (ex.getKind()) {
			case CONFIG -> HttpStatus.INTERNAL_SERVER_ERROR;
			case RATE_LIMIT -> HttpStatus.TOO_MANY_REQUESTS;
			case UPSTREAM -> HttpStatus.BAD_GATEWAY;
			case INVALID_RESPONSE -> HttpStatus.BAD_GATEWAY;
		};
		log.error("LLM 호출 실패: kind={}", ex.getKind(), ex);
		return body(status, ex.getMessage());
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex) {
		log.error("처리되지 않은 예외", ex);
		return body(HttpStatus.INTERNAL_SERVER_ERROR, "요청을 처리하지 못했습니다.");
	}

	private ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
		return ResponseEntity.status(status).body(Map.of("error", message));
	}
}
