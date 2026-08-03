package com.jobit.common;

import com.jobit.jd.JdParserFallbackConfig.JdParserNotConfiguredException;
import com.jobit.llm.LlmException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
	 * LLM 구현이 등록되지 않은 상태. 사용자 잘못이 아니므로 5xx다 — 재시도해도 소용없다는 것을
	 * 운영자가 로그에서 바로 알 수 있어야 한다.
	 */
	@ExceptionHandler(JdParserNotConfiguredException.class)
	public ResponseEntity<Map<String, String>> handleParserMissing(
			JdParserNotConfiguredException ex) {
		log.error("JdParser 구현이 없습니다. ANTHROPIC_API_KEY 설정을 확인하세요.", ex);
		return body(HttpStatus.INTERNAL_SERVER_ERROR, "공고 분석 기능이 아직 설정되지 않았습니다.");
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
