package com.jobit.llm;

import lombok.Getter;

/**
 * LLM 호출 실패. 컨트롤러가 상태 코드를 정하는 데 쓴다.
 *
 * <p>메시지는 <b>사용자에게 그대로 보여줄 한국어 문장</b>이다 (docs/api.md 공통 규약).
 * 내부 예외 메시지나 스택은 여기 담지 않고 로그로만 남긴다.
 */
@Getter
public class LlmException extends RuntimeException {

	private final Kind kind;

	public LlmException(Kind kind, String userMessage) {
		super(userMessage);
		this.kind = kind;
	}

	public LlmException(Kind kind, String userMessage, Throwable cause) {
		super(userMessage, cause);
		this.kind = kind;
	}

	public enum Kind {

		/** API 키 누락 등 서버 설정 문제. 사용자가 재시도해도 소용없다. */
		CONFIG,

		/** 제공자 레이트 리밋. */
		RATE_LIMIT,

		/** 제공자 장애·타임아웃. */
		UPSTREAM,

		/** 응답이 스키마에 맞지 않아 재시도까지 실패. */
		INVALID_RESPONSE
	}
}
