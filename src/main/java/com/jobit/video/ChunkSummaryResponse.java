package com.jobit.video;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 청크 요약 구조화 출력 (1단계). 배열이 없어 {@code @MaxItems} 도 없다.
 *
 * <p>구조를 더 싣지 않는 이유: 1단계는 압축이 전부다 — 구조(섹션·타임스탬프 배분)는 전체를
 * 본 2단계만 제대로 할 수 있고, 여기서 시키면 청크 경계가 곧 섹션 경계가 되는 나쁜 보고서가
 * 나온다.
 */
public record ChunkSummaryResponse(

		@JsonPropertyDescription("이 구간에서 말한 내용의 요약 3~6문장. 구체적 사실·수치·주장을 보존하고, "
				+ "말버릇·광고·인사는 버린다. 영상에 없는 내용을 지어내지 않는다") String summary) {
}
