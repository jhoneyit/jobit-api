package com.jobit.video;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.jobit.llm.MaxItems;
import java.util.List;

/**
 * 영상 보고서 구조화 출력 (2단계). {@link com.jobit.llm.JsonSchemas} 가 스키마를 파생시킨다.
 *
 * <p>{@code @JsonPropertyDescription} 은 프롬프트의 일부, 배열엔 {@code @MaxItems} —
 * 다른 응답 record 들과 같은 규약이다. 이 record 는 {@code video_summary.report} jsonb 로
 * 그대로 직렬화되어 화면 계약이기도 하다.
 */
public record VideoReportResponse(

		@JsonPropertyDescription("영상 전체를 한 문장으로") String oneLine,

		@JsonPropertyDescription("영상이 다루는 내용의 개요 한 단락 (3~5문장)") String overview,

		// 실제로 터진 자리가 배열 폭주였다 (JD keywords, 2026-08-13) — 여기도 처음부터 상한.
		@MaxItems(12) @JsonPropertyDescription("내용 흐름을 따라가는 섹션 목록 (3~10개)") List<Section> sections,

		@MaxItems(7) @JsonPropertyDescription("시청자가 가져갈 핵심 정리 3~7개. 각 항목 한 문장") List<String> takeaways) {

	public record Section(

			@JsonPropertyDescription("섹션 제목 한 줄") String heading,

			@JsonPropertyDescription("이 섹션이 시작되는 영상 내 시각(초). 자막에 붙은 [t=초] 에서 고른다. "
					+ "확실하지 않으면 null") Integer startSec,

			@JsonPropertyDescription("이 구간에서 말한 내용 요약 2~4문장. 영상에 없는 내용을 지어내지 않는다") String summary) {
	}
}
