package com.jobit.interview;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.jobit.llm.MaxItems;
import java.util.List;

/**
 * LLM 구조화 출력의 형태. {@link com.jobit.llm.JsonSchemas}가 이 클래스에서 JSON Schema를 파생시킨다.
 *
 * <p>{@code @JsonPropertyDescription}은 <b>장식이 아니라 프롬프트의 일부</b>다. 스키마에 실려
 * 모델에게 전달되므로, 필드 의미가 바뀌면 여기부터 고친다 ({@code JdParseResponse}와 같은 규약).
 *
 * <p><b>{@code missed}가 없다.</b> 서버가 {@code covered}의 여집합으로 계산한다 —
 * {@link AnswerScoreNormalizer} 참고.
 *
 * <p><b>covered 는 인덱스가 아니라 인덱스+근거 인용 쌍이다</b> (2026-08-20, 주입 방어 2층).
 * 프롬프트 가드(1층)는 모델 순응에 기대는 방어라 문구가 바뀌면 다시 뚫릴 수 있다 — 인용은
 * 서버가 답변 원문과 대조하므로 모델이 무슨 지시를 따랐든 <b>답변에 없는 근거로는 항목을
 * 짚을 수 없다</b>. 주입 답변에는 기술 내용이 없으므로 인용을 만들 수 없고, covered 가
 * 전부 떨어져 점수 상한이 0 이 된다.
 */
public record AnswerScoreResponse(

		@JsonPropertyDescription("0~100 점수. 짚은 항목 비율에서 시작하되 답변의 깊이를 반영한다") int score,

		// 실제 상한은 뼈대 항목 수(보통 2~4)지만 스키마는 그걸 모른다. 반복 폭주만 끊는다.
		@MaxItems(20) @JsonPropertyDescription("답변이 실제로 짚은 답변 뼈대 항목 목록. "
				+ "확신이 없으면 넣지 않는다") List<Covered> covered,

		@JsonPropertyDescription("한국어 한두 문장. 무엇이 좋았고 무엇이 빠졌는지만 말한다. "
				+ "모범 답변을 대신 써 주지 않는다") String feedback) {

	public record Covered(

			@JsonPropertyDescription("짚은 뼈대 항목의 인덱스 (0부터)") int index,

			@JsonPropertyDescription("그 항목을 짚었다고 판단한 근거 — 답변에서 **그대로** 옮긴 구절. "
					+ "다듬거나 지어내지 않는다. 답변에 없는 구절은 검증에서 버려진다") String quote) {
	}
}
