package com.jobit.video;

import java.util.ArrayList;
import java.util.List;

/**
 * 보고서 재검증 — 형태는 스키마가, 내용은 여기가 본다 (다른 Normalizer 들과 같은 자리).
 *
 * <p>타임스탬프 검증이 핵심이다: 모델이 영상 길이 밖의 초를 지어내면 딥링크가 영상 끝으로
 * 튄다 — 범위 밖은 null 로 눕힌다 (섹션 자체는 살린다: 요약 내용은 멀쩡한데 좌표만 틀린
 * 것이라, 버리면 멀쩡한 내용을 잃는다).
 */
final class VideoReportNormalizer {

	private VideoReportNormalizer() {
	}

	/** 불합격 사유. 통과하면 null — {@code RewriteNormalizer} 와 같은 재시도 유도 규약이다. */
	static String problem(VideoReportResponse response) {
		if (response == null) {
			return "응답이 없다";
		}
		if (response.oneLine() == null || response.oneLine().isBlank()) {
			return "oneLine 이 비어 있다";
		}
		if (response.overview() == null || response.overview().isBlank()) {
			return "overview 가 비어 있다";
		}
		if (response.sections() == null || response.sections().isEmpty()) {
			return "sections 가 비어 있다";
		}
		if (response.takeaways() == null || response.takeaways().isEmpty()) {
			return "takeaways 가 비어 있다";
		}
		return null;
	}

	static VideoReportResponse normalize(VideoReportResponse response, int durationSec) {
		List<VideoReportResponse.Section> sections = new ArrayList<>();
		for (VideoReportResponse.Section section : response.sections()) {
			if (section.summary() == null || section.summary().isBlank()) {
				continue; // 내용 없는 섹션은 화면에서 빈 칸이다 — 버린다.
			}
			Integer start = section.startSec();
			if (start != null && (start < 0 || (durationSec > 0 && start > durationSec))) {
				start = null;
			}
			sections.add(new VideoReportResponse.Section(
					section.heading() == null || section.heading().isBlank() ? "구간"
							: section.heading().strip(),
					start, section.summary().strip()));
		}

		List<String> takeaways = response.takeaways().stream()
			.filter(t -> t != null && !t.isBlank())
			.map(String::strip)
			.toList();

		return new VideoReportResponse(response.oneLine().strip(), response.overview().strip(),
				sections, takeaways);
	}
}
