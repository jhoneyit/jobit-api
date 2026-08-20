package com.jobit.video;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** URL 표기가 여럿이라 ID 추출이 곧 캐시 키 정규화다 — 여기가 틀리면 캐시가 갈라진다. */
class VideoIdsTest {

	@Test
	@DisplayName("주요 유튜브 URL 형태를 전부 같은 ID 로 정규화한다")
	void extractsFromAllUrlForms() {
		String id = "dQw4w9WgXcQ";
		for (String url : new String[] {
				"https://www.youtube.com/watch?v=dQw4w9WgXcQ",
				"https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s",
				"https://www.youtube.com/watch?list=PL123&v=dQw4w9WgXcQ",
				"https://youtu.be/dQw4w9WgXcQ",
				"https://youtu.be/dQw4w9WgXcQ?si=abc",
				"https://www.youtube.com/shorts/dQw4w9WgXcQ",
				"https://www.youtube.com/embed/dQw4w9WgXcQ",
				"https://www.youtube.com/live/dQw4w9WgXcQ",
				"youtu.be/dQw4w9WgXcQ",
				"  https://youtu.be/dQw4w9WgXcQ  " }) {
			assertThat(VideoIds.extract(url)).as(url).isEqualTo(id);
		}
	}

	@Test
	@DisplayName("유튜브가 아니면 null — 예외가 아니라 호출부가 사용자 문구로 바꾼다")
	void rejectsNonYoutube() {
		for (String url : new String[] { null, "", "https://vimeo.com/12345",
				"https://example.com/watch?v=dQw4w9WgXcQ", "그냥 텍스트",
				"https://www.youtube.com/watch?v=too-short" }) {
			assertThat(VideoIds.extract(url)).as(String.valueOf(url)).isNull();
		}
	}
}
