package com.jobit.video;

import java.util.List;

/**
 * 자막 → 보고서 (영상 요약의 LLM 구간). 인터페이스인 이유는 {@code GapJudge} 와 같다 —
 * {@code ollama.base-url} 이 없으면 {@link VideoSummarizerFallbackConfig} 가 자리를 지킨다.
 */
public interface VideoSummarizer {

	VideoReportResponse summarize(Request request);

	record Request(String title, String channel, int durationSec,
			List<TranscriptSegment> segments) {
	}
}
