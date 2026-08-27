package com.jobit.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 스트림 조각은 JSON 문법 경계를 무시하고 잘려 온다 — 이 파서의 존재 이유가 그 경계 처리다.
 * 그래서 "한 글자씩 먹여도 통째로 먹인 것과 같다"를 모든 케이스의 기준으로 삼는다.
 */
class JsonStringFieldStreamTest {

	private static String extract(String json, int chunkSize) {
		StringBuilder got = new StringBuilder();
		JsonStringFieldStream stream = new JsonStringFieldStream("answer", got::append);
		for (int i = 0; i < json.length(); i += chunkSize) {
			stream.feed(json.substring(i, Math.min(json.length(), i + chunkSize)));
		}
		return got.toString();
	}

	@Test
	@DisplayName("answer 필드의 내용만 뽑는다 — refs 는 흘려보내지 않는다")
	void extractsOnlyTargetField() {
		String json = "{\"answer\": \"발표는 3분 안에 끝낸다.\", \"refs\": [120, 480]}";
		assertThat(extract(json, json.length())).isEqualTo("발표는 3분 안에 끝낸다.");
	}

	@Test
	@DisplayName("한 글자씩 잘려 와도 결과가 같다 — 조각 경계는 문법을 무시한다")
	void charByCharMatchesWhole() {
		String json = "{\"answer\":\"이스케이프 \\\"인용\\\"과 \\n 줄바꿈, \\\\ 역슬래시\",\"refs\":[]}";
		String whole = extract(json, json.length());
		assertThat(whole).isEqualTo("이스케이프 \"인용\"과 \n 줄바꿈, \\ 역슬래시");
		assertThat(extract(json, 1)).isEqualTo(whole);
		assertThat(extract(json, 3)).isEqualTo(whole);
	}

	@Test
	@DisplayName("유니코드 이스케이프가 조각 중간에서 끊겨도 복원된다")
	void unicodeEscapeAcrossChunks() {
		// 한글 = "한글" — 서로게이트가 아닌 BMP 문자 둘
		String json = "{\"answer\":\"\\uD55C\\uAE00\"}";
		assertThat(extract(json, 1)).isEqualTo("한글");
		assertThat(extract(json, 2)).isEqualTo("한글");
	}

	@Test
	@DisplayName("닫는 따옴표 뒤의 내용은 무시한다 — 필드 하나로 끝이다")
	void stopsAtClosingQuote() {
		List<String> deltas = new ArrayList<>();
		JsonStringFieldStream stream = new JsonStringFieldStream("answer", deltas::add);
		stream.feed("{\"answer\":\"답\"");
		stream.feed(",\"refs\":[1],\"answer\":\"두 번째는 무시\"}");
		assertThat(String.join("", deltas)).isEqualTo("답");
	}

	@Test
	@DisplayName("내용이 나오기 전에는 onDelta 를 부르지 않는다")
	void silentBeforeContent() {
		List<String> deltas = new ArrayList<>();
		JsonStringFieldStream stream = new JsonStringFieldStream("answer", deltas::add);
		stream.feed("{\"answ");
		stream.feed("er\"");
		stream.feed(" : ");
		assertThat(deltas).isEmpty();
		stream.feed("\"시");
		assertThat(deltas).containsExactly("시");
	}
}
