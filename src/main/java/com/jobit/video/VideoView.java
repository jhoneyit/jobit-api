package com.jobit.video;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * 영상 요약 응답 형태 (docs/api.md "영상 요약").
 *
 * <p>{@code report} 는 jsonb 문자열을 객체로 풀어 내린다 — {@code /api/jd/parse} 의
 * {@code parsed} 와 같은 계약이다 (프론트가 문자열을 다시 파싱하지 않는다).
 */
public final class VideoView {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private VideoView() {
	}

	public static Detail of(VideoSummary summary) {
		return new Detail(summary.getId(), summary.getVideoId(), summary.getUrl(),
				summary.getTitle(), summary.getChannel(), summary.getDurationSec(),
				summary.getStatus(),
				summary.getTranscriptSource(), summary.getErrorMessage(), report(summary),
				summary.getCreatedAt());
	}

	public static Row row(VideoSubmission submission) {
		VideoSummary summary = submission.getSummary();
		return new Row(summary.getId(), summary.getVideoId(), summary.getTitle(),
				summary.getChannel(), summary.getDurationSec(), summary.getStatus(),
				submission.getCreatedAt());
	}

	private static Map<String, Object> report(VideoSummary summary) {
		if (summary.getReport() == null) {
			return null;
		}
		return MAPPER.readValue(summary.getReport(), Map.class);
	}

	/**
	 * @param report DONE 일 때만 있다 ({@code VideoReportResponse} 형태의 객체)
	 * @param errorMessage FAILED 일 때만 있다. 그대로 화면에 띄워도 되는 문구다
	 */
	public record Detail(UUID summaryId, String videoId, String url, String title, String channel,
			Integer durationSec, VideoSummary.Status status, VideoSummary.Source source,
			String errorMessage, Map<String, Object> report, OffsetDateTime createdAt) {
	}

	public record Row(UUID summaryId, String videoId, String title, String channel,
			Integer durationSec, VideoSummary.Status status, OffsetDateTime submittedAt) {
	}
}
