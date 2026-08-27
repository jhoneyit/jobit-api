package com.jobit.video;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.jobit.llm.MaxItems;
import java.util.List;

/**
 * 영상 QnA 구조화 출력. refs 의 시각은 서버가 발췌 목록과 대조해 재검증한다.
 *
 * <p><b>{@code answer} 가 첫 컴포넌트여야 한다.</b> 스트리밍이 {@code JsonStringFieldStream}
 * 으로 이 필드를 문자열 매칭으로 찾는데, 앞에 다른 문자열 값이 오면 그 안의 "answer" 에
 * 속을 수 있다 — 순서가 곧 방어다 (스키마 프로퍼티 순서는 컴포넌트 순서를 따른다).
 */
public record VideoQnaResponse(

		@JsonPropertyDescription("질문에 대한 답 (한국어 2~5문장). 발췌에 근거가 없으면 "
				+ "영상에서 찾지 못했다고 말한다") String answer,

		@MaxItems(3) @JsonPropertyDescription("답의 근거가 된 발췌의 t 값(초). "
				+ "발췌에 실제로 있는 값만") List<Integer> refs) {
}
