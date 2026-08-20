package com.jobit.video;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VideoReportNormalizerTest {

	private static VideoReportResponse.Section section(String heading, Integer start,
			String summary) {
		return new VideoReportResponse.Section(heading, start, summary);
	}

	@Test
	@DisplayName("영상 길이 밖의 타임스탬프는 null 로 눕힌다 — 섹션은 살린다")
	void clampsOutOfRangeTimestamps() {
		VideoReportResponse normalized = VideoReportNormalizer.normalize(new VideoReportResponse(
				"한 줄", "개요", List.of(section("정상", 50, "내용"), section("초과", 999, "내용"),
						section("음수", -3, "내용")),
				List.of("정리")), 100);

		assertThat(normalized.sections()).hasSize(3);
		assertThat(normalized.sections().get(0).startSec()).isEqualTo(50);
		assertThat(normalized.sections().get(1).startSec()).isNull();
		assertThat(normalized.sections().get(2).startSec()).isNull();
	}

	@Test
	@DisplayName("길이를 모르는 영상(0)은 상한 검사를 건너뛴다 — 라이브 녹화가 그렇다")
	void skipsUpperBoundWhenDurationUnknown() {
		VideoReportResponse normalized = VideoReportNormalizer.normalize(new VideoReportResponse(
				"한 줄", "개요", List.of(section("구간", 5000, "내용")), List.of("정리")), 0);

		assertThat(normalized.sections().getFirst().startSec()).isEqualTo(5000);
	}

	@Test
	@DisplayName("내용 없는 섹션은 버리고, 빈 takeaway 도 거른다")
	void dropsBlankParts() {
		VideoReportResponse normalized = VideoReportNormalizer.normalize(new VideoReportResponse(
				"한 줄", "개요", List.of(section("빈 섹션", 10, "  "), section(null, 20, "내용")),
				List.of(" ", "정리 하나")), 100);

		assertThat(normalized.sections()).hasSize(1);
		assertThat(normalized.sections().getFirst().heading()).isEqualTo("구간");
		assertThat(normalized.takeaways()).containsExactly("정리 하나");
	}

	@Test
	@DisplayName("problem — 핵심 필드가 비면 재시도 대상이다")
	void reportsProblems() {
		assertThat(VideoReportNormalizer.problem(null)).isNotNull();
		assertThat(VideoReportNormalizer.problem(new VideoReportResponse(" ", "개요",
				List.of(section("h", 0, "s")), List.of("t")))).isNotNull();
		assertThat(VideoReportNormalizer.problem(new VideoReportResponse("한 줄", "개요",
				List.of(), List.of("t")))).isNotNull();
		assertThat(VideoReportNormalizer.problem(new VideoReportResponse("한 줄", "개요",
				List.of(section("h", 0, "s")), List.of("t")))).isNull();
	}
}
