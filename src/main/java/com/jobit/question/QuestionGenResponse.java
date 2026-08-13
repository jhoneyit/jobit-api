package com.jobit.question;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.jobit.llm.MaxItems;
import java.util.List;

/**
 * 질문 생성 구조화 출력의 형태 (스펙 §4.2). {@link com.jobit.llm.JsonSchemas}가 이 클래스에서 JSON Schema를 파생시킨다.
 *
 * <p>{@code @JsonPropertyDescription}은 <b>장식이 아니라 프롬프트의 일부</b>다. 스키마에 실려
 * 모델에게 전달되므로, 필드 의미가 바뀌면 여기부터 고친다.
 *
 * <p><b>배열의 상한은 {@link MaxItems}로 건다.</b> "구조화 출력이 지원하지 않는다"고 적혀 있던 것은
 * Anthropic 시절의 사실이고, Ollama 는 개수 제약까지 GBNF 문법에 반영한다. 정확한 개수
 * ({@link QuestionGenPrompts#QUESTION_COUNT}개)는 여전히 문법으로 못 박을 수 없으므로
 * {@link QuestionGenPrompts#SYSTEM}에 글로 적는다 — 여기서 거는 것은 <b>폭주를 끊는 천장</b>이다.
 */
public record QuestionGenResponse(

		// QUESTION_COUNT(10)의 두 배. 정확한 개수는 프롬프트가 맡고 여기는 천장만 맡는다.
		@MaxItems(20) @JsonPropertyDescription("면접 질문 목록") List<RawQuestion> questions) {

	/**
	 * 스트리밍 중에는 이 타입으로 원소 하나씩 역직렬화한다.
	 *
	 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 를 붙인 이유: 모델이 스키마에 없는
	 * 필드를 덧붙여도 그 질문 하나를 통째로 버리지 않기 위해서다. 스트리밍이라 이미 사용자에게
	 * 흘러간 뒤일 수도 있다.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record RawQuestion(

			@JsonPropertyDescription("이 질문이 나온 요구사항의 번호(입력에 붙은 [n]). "
					+ "특정 요구사항에서 나온 게 아니면 -1") Integer requirementIndex,

			@JsonPropertyDescription("면접관이 실제로 물어볼 법한 질문 한 문장") String text,

			@JsonPropertyDescription("CS=전산 기초, STACK=해당 스택 지식, EXPERIENCE=경험 확인, "
					+ "DESIGN=설계/트레이드오프, CULTURE=협업·일하는 방식") Question.Category category,

			@JsonPropertyDescription("1=신입도 답할 수준, 5=시니어에게도 어려움") Integer difficulty,

			@MaxItems(3) @JsonPropertyDescription("면접관이 이어서 물을 꼬리질문 1~3개") List<String> followups,

			@JsonPropertyDescription("답변 뼈대. 완성된 답변이 아니라 '무엇을 짚어야 하는지' "
					+ "핵심 포인트 2~4개") @MaxItems(4) List<String> answerOutline) {

		/**
		 * 서버측 재검증 (스펙 §6).
		 *
		 * <p>구조화 출력이 필드 존재는 보장하지만 <b>내용이 비어 있는 것은 막지 못한다.</b>
		 * 질문 문장이 빈 항목이 화면에 뜨면 그 자체가 버그로 보인다. 난이도 범위도 여기서 본다 —
		 * 스키마의 enum 이 어긋나는 경우가 드물게 있다.
		 */
		public boolean isValid() {
			return text != null && !text.isBlank() && category != null && difficulty != null
					&& difficulty >= 1 && difficulty <= 5;
		}

		/** 모델이 빠뜨렸을 때 화면이 null 을 만나지 않도록 빈 배열로 맞춘다. */
		public List<String> safeFollowups() {
			return followups == null ? List.of() : followups;
		}

		public List<String> safeAnswerOutline() {
			return answerOutline == null ? List.of() : answerOutline;
		}
	}
}
