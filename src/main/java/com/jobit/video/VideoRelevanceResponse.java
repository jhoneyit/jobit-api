package com.jobit.video;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 주제 판정 구조화 출력. 배열이 없어 {@code @MaxItems} 도 없다.
 *
 * <p>{@code relevant} 가 원시 boolean 인 것은 의도다 — 스키마가 널을 허용하지 않아
 * 모델이 "모름"을 낼 수 없다. 애매한 경우의 처리는 값이 아니라 프롬프트 규칙이 맡는다
 * (메타 단계: 확실할 때만 false).
 */
public record VideoRelevanceResponse(

		@JsonPropertyDescription("면접·취업·커리어·개발 기술 학습과 관련 있으면 true") boolean relevant,

		@JsonPropertyDescription("판정 근거 한 문장 (한국어). 사용자에게 그대로 보여준다") String reason) {
}
