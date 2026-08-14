package com.jobit.gap;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 리라이트 구조화 출력의 형태 (스펙 §4.4). {@link com.jobit.llm.JsonSchemas} 가 이 클래스에서
 * JSON Schema 를 파생시킨다.
 *
 * <p>{@code @JsonPropertyDescription} 은 <b>장식이 아니라 프롬프트의 일부</b>다. 배열이 없어
 * {@code @MaxItems} 도 없다 ({@code GapJudgeResponse} 와 같다).
 */
public record RewriteResponse(

		@JsonPropertyDescription("고쳐 쓴 문장 하나. 원문에 없는 수치·사실을 지어내지 않고, "
				+ "숫자가 필요한 자리는 [값의 이름] 형태의 자리 표시로 남긴다") String suggested,

		@JsonPropertyDescription("무엇을 왜 바꿨는지 한두 문장 (한국어). "
				+ "지원자를 평가하는 말투를 쓰지 않는다") String reason) {
}
