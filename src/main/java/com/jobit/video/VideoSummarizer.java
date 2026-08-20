package com.jobit.video;

import java.util.List;

/**
 * 자막 → 보고서 (영상 요약의 LLM 구간). 인터페이스인 이유는 {@code GapJudge} 와 같다 —
 * {@code ollama.base-url} 이 없으면 {@link VideoSummarizerFallbackConfig} 가 자리를 지킨다.
 */
public interface VideoSummarizer {

	VideoReportResponse summarize(Request request);

	/**
	 * 주제 게이트 (면접·취업 관련만 통과).
	 *
	 * @param transcriptHead null 이면 메타 단계다 — 제목·설명만으로 <b>확실할 때만</b> 거부한다.
	 *                       값이 있으면 내용 단계로, 실제 자막 기준으로 판정한다
	 */
	VideoRelevanceResponse judgeRelevance(YtDlp.Meta meta, String transcriptHead);

	record Request(String title, String channel, int durationSec,
			List<TranscriptSegment> segments) {
	}
}
