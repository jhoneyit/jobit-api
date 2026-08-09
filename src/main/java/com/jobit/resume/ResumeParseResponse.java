package com.jobit.resume;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * 이력서 분해 LLM 구조화 출력의 형태 (스펙 §3.3). SDK 가 이 클래스에서 JSON Schema 를 파생시킨다.
 *
 * <p>{@code @JsonPropertyDescription} 은 <b>장식이 아니라 프롬프트의 일부</b>다 —
 * {@code JdParseResponse} 와 같다. 개수·길이 제약은 구조화 출력이 지원하지 않으므로
 * {@link ResumeParsePrompts#SYSTEM} 에 글로 적고 {@link AnthropicResumeParser} 가 재검증한다.
 */
public record ResumeParseResponse(

		@JsonPropertyDescription("경험 문장 목록. 이력서에 나온 순서를 유지한다") List<RawBullet> bullets) {

	public record RawBullet(

			@JsonPropertyDescription("경험 문장 하나. 원문의 표현과 수치를 유지한다. "
					+ "이력서에 없는 내용을 지어내지 않는다") String text,

			@JsonPropertyDescription("이 문장이 속한 회사·프로젝트명. 알 수 없으면 null") String company,

			@JsonPropertyDescription("기간. 이력서에 적힌 표기 그대로 (예: '2022.03 ~ 2024.08'). "
					+ "날짜로 변환하지 않는다. 없으면 null") String period) {
	}
}
