package com.jobit.jd;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.jobit.llm.MaxItems;
import java.util.List;

/**
 * LLM 구조화 출력의 형태 (스펙 §4.1). {@link com.jobit.llm.JsonSchemas}가 이 클래스에서 JSON Schema를 파생시킨다.
 *
 * <p>{@code @JsonPropertyDescription}은 <b>장식이 아니라 프롬프트의 일부</b>다. 스키마에 실려
 * 모델에게 전달되므로, 필드 의미가 바뀌면 여기부터 고친다.
 *
 * <p><b>배열의 상한은 {@link MaxItems}로 건다.</b> 예전에는 "개수 제약은 구조화 출력이 지원하지
 * 않는다"고 적혀 있었지만 Ollama 에서는 통한다 — 상한이 없어 {@code keywords}가 같은 문구를 100번
 * 반복하며 출력 상한까지 간 일이 있었다. <b>하한(최소 8개 등)과 길이 제약은 여전히 문법으로 걸 수
 * 없으므로</b> {@link JdParsePrompts#SYSTEM}에 글로 적고 {@link OllamaJdParser}가 재검증한다.
 */
public record JdParseResponse(

		@JsonPropertyDescription("공고 메타데이터") ParsedMeta parsed,

		// 프롬프트가 "보통 8~20개"라고 적어 둔 값의 넉넉한 상한이다. 긴 공고도 30을 넘기지 않는다.
		@MaxItems(30) @JsonPropertyDescription("요구사항 목록. 공고에 나온 순서를 유지한다") List<RawRequirement> requirements) {

	public record ParsedMeta(

			@JsonPropertyDescription("회사명. 공고에 없으면 null") String company,

			@JsonPropertyDescription("채용 포지션명. 공고에 없으면 null") String title,

			@JsonPropertyDescription("기술 스택. 공고에 명시된 것만. 정규화된 표기를 쓴다 "
					+ "(예: 'Spring Boot', 'Kubernetes', 'PostgreSQL')") @MaxItems(20) List<String> stack,

			@JsonPropertyDescription("요구 연차. '3년 이상'이면 {min:3,max:null}. "
					+ "명시가 없으면 객체 전체를 null") YearsOfExperience yearsOfExperience,

			@JsonPropertyDescription("서비스 도메인 한 줄 (예: '핀테크 결제', 'B2B SaaS 인프라')") String domain,

			// 폭주가 실제로 터진 자리다 — 설명의 "5~10개"를 모델이 351개까지 늘렸다.
			@MaxItems(10) @JsonPropertyDescription("공고 전반을 대표하는 키워드 5~10개") List<String> keywords) {
	}

	public record YearsOfExperience(Integer min, Integer max) {
	}

	public record RawRequirement(

			@JsonPropertyDescription("요구사항 한 줄. 공고 문장을 그대로 베끼지 말고 "
					+ "판정 가능한 형태로 정리한다") String text,

			@JsonPropertyDescription("REQUIRED=자격요건, PREFERRED=우대사항, "
					+ "RESPONSIBILITY=담당업무") Requirement.Kind kind,

			@MaxItems(5) @JsonPropertyDescription("이 요구사항 매칭에 쓸 키워드 1~5개") List<String> keywords) {
	}
}
