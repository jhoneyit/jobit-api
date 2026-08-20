package com.jobit.video;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class YtDlpTest {

	@Test
	@DisplayName("트랙 선택 — 수동 ko > 수동 en > 아무 수동 > 자동 원어 > 자동 ko/en")
	void chooseTrackPriority() {
		assertThat(YtDlp.chooseTrack(probe(List.of("en", "ko"), List.of())))
			.contains(new YtDlp.Track("ko", false));
		assertThat(YtDlp.chooseTrack(probe(List.of("de", "en"), List.of())))
			.contains(new YtDlp.Track("en", false));
		assertThat(YtDlp.chooseTrack(probe(List.of("de"), List.of("en-orig"))))
			.contains(new YtDlp.Track("de", false));
		// 자동 번역 변형(ko-en 등)이 아니라 원어(-orig)를 고른다 — 번역 엔드포인트는 429 를 던진다.
		assertThat(YtDlp.chooseTrack(probe(List.of(), List.of("ko", "ja", "en-orig"))))
			.contains(new YtDlp.Track("en-orig", true));
		assertThat(YtDlp.chooseTrack(probe(List.of(), List.of("ja", "ko"))))
			.contains(new YtDlp.Track("ko", true));
		assertThat(YtDlp.chooseTrack(probe(List.of(), List.of("ja", "de")))).isEmpty();
	}

	@Test
	@DisplayName("json3 파싱 — tStartMs 를 초로, segs 를 한 줄로")
	void parsesJson3() {
		String json = """
				{"events":[
				  {"tStartMs":0,"segs":[{"utf8":"안녕"},{"utf8":"하세요"}]},
				  {"tStartMs":1500},
				  {"tStartMs":63000,"segs":[{"utf8":"다음\\n내용"}]},
				  {"tStartMs":90000,"segs":[{"utf8":"  "}]}
				]}""";

		List<TranscriptSegment> segments = YtDlp.parseJson3(json);

		assertThat(segments).containsExactly(new TranscriptSegment(0, "안녕하세요"),
				new TranscriptSegment(63, "다음 내용"));
	}

	private static YtDlp.Probe probe(List<String> manual, List<String> auto) {
		return new YtDlp.Probe(new YtDlp.Meta("t", "c", 100), manual, auto);
	}
}
