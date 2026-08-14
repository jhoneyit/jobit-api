package com.jobit.gap;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 갭 판정 구조화 출력의 형태 (스펙 §4.3). {@link com.jobit.llm.JsonSchemas} 가 이 클래스에서
 * JSON Schema 를 파생시킨다.
 *
 * <p>{@code @JsonPropertyDescription} 은 <b>장식이 아니라 프롬프트의 일부</b>다 —
 * {@code JdParseResponse} 와 같은 규약이다.
 *
 * <p><b>배열이 없다.</b> 요구사항 하나에 판정 하나라 폭주할 배열 자체가 없고, 그래서
 * {@code @MaxItems} 도 없다 — {@code JsonSchemasTest} 의 "모든 배열에 상한" 검사는 이 타입도
 * 훑지만 걸릴 것이 없다.
 *
 * <p><b>{@code evidenceIndex} 가 인덱스인 이유.</b> 문장 ID(uuid)를 그대로 되돌려 받으면 모델이
 * ID 를 한 글자 틀리는 실패가 생기고, 그건 검증으로 걸러도 어느 문장을 가리키려 했는지 복원할 수
 * 없다. 인덱스는 범위 검사 하나로 유효성이 끝난다 — {@code AnswerScoreResponse.covered} 와 같은
 * 판단이다.
 */
public record GapJudgeResponse(

		@JsonPropertyDescription("MET=구체적 근거 문장이 있음, WEAK=언급은 있으나 근거가 약함, "
				+ "MISSING=근거가 되는 문장이 없음") GapItem.Status status,

		@JsonPropertyDescription("근거로 삼은 이력서 문장의 인덱스 (0부터). MET/WEAK 는 반드시 넣고, "
				+ "MISSING 은 null") Integer evidenceIndex,

		@JsonPropertyDescription("판정 이유 한두 문장 (한국어). 무엇이 있어서(없어서) 이렇게 판정했는지만. "
				+ "지원자를 평가하는 말투를 쓰지 않는다") String rationale) {
}
