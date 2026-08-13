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
 * {@link AnswerScoreNormalizer} 참고. 둘 다 받으면 서로 겹치거나 합쳐도 전체가 안 되는 응답을
 * 걸러내야 하는데, 한쪽만 받으면 그 성질이 계산에서 따라 나온다.
 */
public record AnswerScoreResponse(

		@JsonPropertyDescription("0~100 점수. 짚은 항목 비율에서 시작하되 답변의 깊이를 반영한다") int score,

		// 실제 상한은 뼈대 항목 수(보통 2~4)지만 스키마는 그걸 모른다. 범위 밖 인덱스는
		// AnswerScoreNormalizer 가 버리므로, 여기서는 같은 인덱스를 반복하는 폭주만 끊으면 된다.
		@MaxItems(20) @JsonPropertyDescription("답변이 실제로 짚은 답변 뼈대 항목의 인덱스 배열 (0부터). "
				+ "확신이 없으면 넣지 않는다") List<Integer> covered,

		@JsonPropertyDescription("한국어 한두 문장. 무엇이 좋았고 무엇이 빠졌는지만 말한다. "
				+ "모범 답변을 대신 써 주지 않는다") String feedback) {
}
