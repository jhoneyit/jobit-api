package com.jobit.interview;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code question.answer_outline}(jsonb 문자열)을 채점이 쓸 목록으로 편다.
 *
 * <p>이 변환이 따로 있는 이유: 뼈대는 <b>질문 생성 LLM 이 만든 값</b>이라 형태를 우리가 온전히
 * 통제하지 못한다. null, 빈 배열, 원소 null, 객체가 섞인 배열이 모두 들어올 수 있고, 그대로
 * 채점에 넘기면 인덱스가 어긋나 {@code covered}/{@code missed}가 엉뚱한 항목을 가리킨다.
 *
 * <p><b>인덱스가 곧 계약이다.</b> 여기서 만든 목록의 순서가 DB에 저장되는 인덱스의 의미이고,
 * 화면이 뼈대 옆에 ✅/❌를 붙일 때도 같은 순서를 쓴다. 그래서 <b>원소를 버릴 때도 순서를
 * 유지</b>해야 한다 — 중간을 들어내면 그 뒤 인덱스가 전부 한 칸씩 밀린다. 빈 항목은 버리지 않고
 * 빈 문자열로 남기는 이유가 이것이다.
 */
final class AnswerOutlines {

	/** 전역 매퍼를 주입받지 않는다 — 전역 설정이 바뀌면 이 해석이 조용히 따라 바뀐다. */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final TypeReference<List<Object>> LIST_OF_ANY = new TypeReference<>() {
	};

	private AnswerOutlines() {
	}

	/**
	 * @param answerOutlineJson {@code ["핵심 1", "핵심 2"]} 형태. null·빈 문자열 가능
	 * @return 항목 목록. 파싱할 수 없거나 배열이 아니면 <b>빈 목록</b>이다 — 예외를 던지지 않는
	 *         이유는 호출부가 "뼈대 없는 질문"과 같은 방식으로 다루면 되기 때문이다
	 */
	static List<String> parse(String answerOutlineJson) {
		if (answerOutlineJson == null || answerOutlineJson.isBlank()) {
			return List.of();
		}

		List<Object> raw;
		try {
			raw = MAPPER.readValue(answerOutlineJson, LIST_OF_ANY);
		}
		catch (Exception ex) {
			// 배열이 아닌 jsonb(객체·문자열)가 들어와도 여기로 떨어진다.
			return List.of();
		}

		List<String> items = new ArrayList<>(raw.size());
		for (Object item : raw) {
			// 순서를 유지해야 인덱스가 어긋나지 않는다 — 중간을 들어내면 뒤가 한 칸씩 밀린다.
			items.add(item == null ? "" : String.valueOf(item).strip());
		}
		return List.copyOf(items);
	}
}
