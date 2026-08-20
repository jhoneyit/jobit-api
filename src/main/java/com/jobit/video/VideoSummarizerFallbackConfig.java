package com.jobit.video;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 설정이 없을 때 {@link VideoSummarizer} 자리를 채운다. 호출되면 예외 —
 * 빈 보고서가 전역 캐시(video_id 유니크)에 굳으면 안 된다 ({@code GapJudgeFallbackConfig} 와 같은 판단).
 */
@Configuration
@Slf4j
public class VideoSummarizerFallbackConfig {

	@Bean
	@ConditionalOnMissingBean(VideoSummarizer.class)
	public VideoSummarizer unavailableVideoSummarizer() {
		log.warn("VideoSummarizer 구현이 없습니다. 영상 요약을 호출하면 실패합니다 (완료된 보고서 조회는 정상 동작합니다).");
		return new VideoSummarizer() {

			@Override
			public VideoReportResponse summarize(Request request) {
				throw new VideoSummarizerNotConfiguredException();
			}

			@Override
			public VideoRelevanceResponse judgeRelevance(YtDlp.Meta meta, String transcriptHead) {
				throw new VideoSummarizerNotConfiguredException();
			}
		};
	}

	public static class VideoSummarizerNotConfiguredException extends IllegalStateException {

		public VideoSummarizerNotConfiguredException() {
			super("VideoSummarizer 구현이 등록되지 않았습니다. LLM 클라이언트를 붙여야 영상 요약이 동작합니다.");
		}
	}
}
