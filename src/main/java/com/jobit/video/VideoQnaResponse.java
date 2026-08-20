package com.jobit.video;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.jobit.llm.MaxItems;
import java.util.List;

/** 영상 QnA 구조화 출력. refs 의 시각은 서버가 발췌 목록과 대조해 재검증한다. */
public record VideoQnaResponse(

		@JsonPropertyDescription("질문에 대한 답 (한국어 2~5문장). 발췌에 근거가 없으면 "
				+ "영상에서 찾지 못했다고 말한다") String answer,

		@MaxItems(3) @JsonPropertyDescription("답의 근거가 된 발췌의 t 값(초). "
				+ "발췌에 실제로 있는 값만") List<Integer> refs) {
}
