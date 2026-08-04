package com.jobit.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 증분 파서 검증.
 *
 * <p><b>API 호출 없이 여기서 다 끝낸다.</b> 이 파서는 "문자열 조각을 넣으면 완성된 원소를 뱉는"
 * 순수 함수라, 실제 LLM 스트림을 흉내 낸 조각만 있으면 검증이 된다. 스트리밍 경로에서 제일 깨지기
 * 쉬운 부분이 여기라 경우를 넉넉히 잡아 둔다.
 */
class IncrementalArrayParserTest {

	/** 실제 스트림처럼 임의 위치에서 잘라 넣는다. 조각 경계가 어디든 결과가 같아야 한다. */
	private List<String> feedInChunks(String json, int chunkSize) {
		IncrementalArrayParser parser = new IncrementalArrayParser("questions");
		List<String> all = new ArrayList<>();
		for (int i = 0; i < json.length(); i += chunkSize) {
			all.addAll(parser.push(json.substring(i, Math.min(i + chunkSize, json.length()))));
		}
		return all;
	}

	@Test
	@DisplayName("원소가 닫히는 순간 하나씩 나온다 — 전부 받을 때까지 기다리지 않는다")
	void emitsEachElementAsItCloses() {
		IncrementalArrayParser parser = new IncrementalArrayParser("questions");

		assertThat(parser.push("{\"questions\":[")).isEmpty();
		assertThat(parser.push("{\"text\":\"첫 번째\"}")).containsExactly("{\"text\":\"첫 번째\"}");
		assertThat(parser.push(",{\"text\":\"두 번")).isEmpty();
		assertThat(parser.push("째\"}]}")).containsExactly("{\"text\":\"두 번째\"}");
	}

	@Test
	@DisplayName("문자열 안의 중괄호에 속지 않는다")
	void ignoresBracesInsideStrings() {
		String json = """
				{"questions":[{"text":"JSON 은 {key: value} 형태입니다"},{"text":"두 번째"}]}""";

		assertThat(feedInChunks(json, 7)).hasSize(2)
			.first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
			.contains("{key: value}");
	}

	@Test
	@DisplayName("이스케이프된 따옴표에 속지 않는다")
	void handlesEscapedQuotes() {
		String json = """
				{"questions":[{"text":"그는 \\"안녕\\" 이라고 했다"},{"text":"둘째"}]}""";

		List<String> out = feedInChunks(json, 3);

		assertThat(out).hasSize(2);
		// 역슬래시 자체가 이스케이프된 경우도 문자열 종료로 오인하면 안 된다.
		assertThat(IncrementalArrayParser.read(out.get(0), Item.class).text())
			.isEqualTo("그는 \"안녕\" 이라고 했다");
	}

	@Test
	@DisplayName("역슬래시로 끝나는 문자열도 정상 처리한다")
	void handlesTrailingBackslash() {
		String json = """
				{"questions":[{"text":"경로는 C:\\\\temp\\\\ 입니다"},{"text":"둘째"}]}""";

		assertThat(feedInChunks(json, 5)).hasSize(2);
	}

	@Test
	@DisplayName("중첩 객체가 있어도 바깥 원소 단위로 끊는다")
	void countsOnlyTopLevelElements() {
		String json = """
				{"questions":[{"text":"q","meta":{"a":{"b":1}}},{"text":"q2","meta":{}}]}""";

		assertThat(feedInChunks(json, 4)).hasSize(2);
	}

	@Test
	@DisplayName("조각 크기와 무관하게 같은 결과가 나온다")
	void isIndependentOfChunkBoundaries() {
		String json = """
				{"questions":[{"text":"하나"},{"text":"둘"},{"text":"셋"}]}""";

		for (int size = 1; size <= json.length(); size++) {
			assertThat(feedInChunks(json, size)).as("조각 크기 %d", size).hasSize(3);
		}
	}

	@Test
	@DisplayName("배열 키 앞에 다른 필드가 있어도 찾는다")
	void findsArrayAfterOtherFields() {
		String json = """
				{"note":"앞에 오는 필드 [괄호 포함]","questions":[{"text":"하나"}]}""";

		assertThat(feedInChunks(json, 6)).hasSize(1);
	}

	@Test
	@DisplayName("배열이 닫히기 전에 스트림이 끊기면 truncated 로 알린다")
	void reportsTruncation() {
		IncrementalArrayParser parser = new IncrementalArrayParser("questions");
		parser.push("{\"questions\":[{\"text\":\"하나\"},{\"text\":\"둘");

		assertThat(parser.isTruncated()).isTrue();
	}

	@Test
	@DisplayName("배열이 정상으로 닫히면 truncated 가 아니다")
	void notTruncatedWhenClosed() {
		IncrementalArrayParser parser = new IncrementalArrayParser("questions");
		parser.push("{\"questions\":[{\"text\":\"하나\"}]}");

		assertThat(parser.isTruncated()).isFalse();
	}

	@Test
	@DisplayName("배열이 시작도 안 했으면 truncated 가 아니다 — 빈 응답과 잘린 응답은 다르다")
	void notTruncatedBeforeArrayStarts() {
		IncrementalArrayParser parser = new IncrementalArrayParser("questions");
		parser.push("설명만 있고 배열은 없는 응답");

		assertThat(parser.isTruncated()).isFalse();
	}

	@Test
	@DisplayName("스키마에 어긋나는 원소는 그것만 버리고 null 을 준다")
	void dropsUnparseableElement() {
		assertThat(IncrementalArrayParser.read("{\"text\":\"정상\"}", Item.class)).isNotNull();
		assertThat(IncrementalArrayParser.read("{\"text\":", Item.class)).isNull();
	}

	private record Item(String text) {
	}
}
