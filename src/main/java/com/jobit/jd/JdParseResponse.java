package com.jobit.jd;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * LLM 구조화 출력의 형태 (스펙 §4.1). {@link com.jobit.llm.JsonSchemas}가 이 클래스에서 JSON Schema를 파생시킨다.
 *
 * <p>{@code @JsonPropertyDescription}은 <b>장식이 아니라 프롬프트의 일부</b>다. 스키마에 실려
 * 모델에게 전달되므로, 필드 의미가 바뀌면 여기부터 고친다.
 *
 * <p>개수·길이 제약(최소 8개 등)은 여기 넣지 않는다 — 구조화 출력이 지원하지 않는다.
 * 그런 규칙은 {@link JdParsePrompts#SYSTEM}에 글로 적고, {@link OllamaJdParser}가 재검증한다.
 */
public record JdParseResponse(

		@JsonPropertyDescription("공고 메타데이터") ParsedMeta parsed,

		@JsonPropertyDescription("요구사항 목록. 공고에 나온 순서를 유지한다") List<RawRequirement> requirements) {

	public record ParsedMeta(

			@JsonPropertyDescription("회사명. 공고에 없으면 null") String company,

			@JsonPropertyDescription("채용 포지션명. 공고에 없으면 null") String title,

			@JsonPropertyDescription("기술 스택. 공고에 명시된 것만. 정규화된 표기를 쓴다 "
					+ "(예: 'Spring Boot', 'Kubernetes', 'PostgreSQL')") List<String> stack,

			@JsonPropertyDescription("요구 연차. '3년 이상'이면 {min:3,max:null}. "
					+ "명시가 없으면 객체 전체를 null") YearsOfExperience yearsOfExperience,

			@JsonPropertyDescription("서비스 도메인 한 줄 (예: '핀테크 결제', 'B2B SaaS 인프라')") String domain,

			@JsonPropertyDescription("공고 전반을 대표하는 키워드 5~10개") List<String> keywords) {
	}

	public record YearsOfExperience(Integer min, Integer max) {
	}

	public record RawRequirement(

			@JsonPropertyDescription("요구사항 한 줄. 공고 문장을 그대로 베끼지 말고 "
					+ "판정 가능한 형태로 정리한다") String text,

			@JsonPropertyDescription("REQUIRED=자격요건, PREFERRED=우대사항, "
					+ "RESPONSIBILITY=담당업무") Requirement.Kind kind,

			@JsonPropertyDescription("이 요구사항 매칭에 쓸 키워드 1~5개") List<String> keywords) {
	}
}
