package com.jobit.resume;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.jobit.llm.MaxItems;
import java.util.List;

/**
 * 이력서 분해 LLM 구조화 출력의 형태 (스펙 §3.3). {@link com.jobit.llm.JsonSchemas} 가 이 클래스에서 JSON Schema 를 파생시킨다.
 *
 * <p>{@code @JsonPropertyDescription} 은 <b>장식이 아니라 프롬프트의 일부</b>다 —
 * {@code JdParseResponse} 와 같다. <b>배열 상한은 {@link MaxItems} 로 걸고</b>(Ollama 는 개수 제약을
 * GBNF 문법에 반영한다), 하한·길이 제약은 여전히 문법으로 걸 수 없어 {@link ResumeParsePrompts#SYSTEM}
 * 에 글로 적고 {@link OllamaResumeParser} 가 재검증한다.
 */
public record ResumeParseResponse(

		// 넷 중 유일하게 프롬프트에 개수 규칙이 없다 — 이력서 길이는 사람마다 다르다. 그래서
		// 상한도 가장 느슨하게 잡는다. 100문장이면 실제 이력서로는 이미 과할 만큼 길다.
		@MaxItems(100) @JsonPropertyDescription("경험 문장 목록. 이력서에 나온 순서를 유지한다") List<RawBullet> bullets) {

	public record RawBullet(

			@JsonPropertyDescription("경험 문장 하나. 원문의 표현과 수치를 유지한다. "
					+ "이력서에 없는 내용을 지어내지 않는다") String text,

			@JsonPropertyDescription("이 문장이 속한 회사·프로젝트명. 알 수 없으면 null") String company,

			@JsonPropertyDescription("기간. 이력서에 적힌 표기 그대로 (예: '2022.03 ~ 2024.08'). "
					+ "날짜로 변환하지 않는다. 없으면 null") String period) {
	}
}
