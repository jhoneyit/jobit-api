package com.jobit.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OllamaChatClient} 가 만드는 {@code /api/chat} 요청 본문을 고정한다.
 *
 * <p><b>여기 실리는 값은 전부 조용히 틀릴 수 있다.</b> {@code num_ctx} 가 빠지면 Ollama 기본값
 * (작다)으로 돌아 긴 입력이 앞에서부터 잘리고, {@code think} 가 뒤집히면 느려지거나 품질이
 * 떨어지고, 온도가 0 이 되면 Qwen3 가 반복에 빠진다. 셋 다 에러가 없다 — 요청은 항상 200 이다.
 *
 * <p>HTTP 를 실제로 보내지 않는다. 본문 조립만 검사한다.
 */
class OllamaRequestBodyTest {

	private static final Map<String, Object> SCHEMA = Map.of("type", "object");

	private static OllamaChatClient client() {
		return new OllamaChatClient("http://localhost:11434", 16_384);
	}

	private static OllamaChatClient.Request request(Effort effort) {
		return new OllamaChatClient.Request("qwen3:14b", "시스템 프롬프트", "사용자 메시지", SCHEMA, effort,
				2_000L);
	}

	@Test
	@DisplayName("num_ctx 가 반드시 실린다 — 빠지면 긴 입력이 조용히 잘린다")
	void alwaysCarriesNumCtx() {
		Map<String, Object> body = client().body(request(Effort.LOW), false);

		assertThat(body.get("options")).asInstanceOf(MAP)
			.containsEntry("num_ctx", 16_384)
			.containsEntry("num_predict", 2_000L);
	}

	@Test
	@DisplayName("스키마가 format 으로, 출력 상한이 num_predict 로 나간다")
	void carriesSchemaAndLimit() {
		Map<String, Object> body = client().body(request(Effort.LOW), false);

		assertThat(body).containsEntry("format", SCHEMA);
	}

	@Test
	@DisplayName("HIGH 만 thinking 을 켠다")
	void thinkFollowsEffort() {
		assertThat(client().body(request(Effort.LOW), false)).containsEntry("think", false);
		assertThat(client().body(request(Effort.MEDIUM), false)).containsEntry("think", false);
		assertThat(client().body(request(Effort.HIGH), false)).containsEntry("think", true);
	}

	/**
	 * Qwen3 는 탐욕적 디코딩(temperature=0)에서 같은 문장을 끝없이 반복하는 실패 모드가 있다.
	 * 구조화 출력이니 온도를 낮추자는 직관이 자연스러워서, 나중에 누가 그렇게 "고치는" 것을
	 * 여기서 막는다.
	 */
	@Test
	@DisplayName("온도가 0 이 아니다 — Qwen3 는 탐욕적 디코딩에서 반복에 빠진다")
	void neverUsesGreedyDecoding() {
		for (Effort effort : Effort.values()) {
			assertThat(client().body(request(effort), false).get("options")).asInstanceOf(MAP)
				.as("effort=%s", effort)
				.extracting("temperature")
				.satisfies(t -> assertThat((double) t).isGreaterThan(0.0));
		}
	}

	@Test
	@DisplayName("시스템 프롬프트는 별도 메시지로 앞에 선다")
	void putsSystemMessageFirst() {
		Map<String, Object> body = client().body(request(Effort.LOW), false);

		assertThat(body.get("messages")).asInstanceOf(LIST)
			.extracting("role")
			.containsExactly("system", "user");
	}

	@Test
	@DisplayName("시스템 프롬프트가 없으면 사용자 메시지만 나간다")
	void omitsEmptySystemMessage() {
		OllamaChatClient.Request request = new OllamaChatClient.Request("qwen3:14b", null, "사용자",
				SCHEMA, Effort.LOW, 2_000L);

		assertThat(client().body(request, false).get("messages")).asInstanceOf(LIST)
			.extracting("role")
			.containsExactly("user");
	}

	@Test
	@DisplayName("stream 플래그가 그대로 나간다 — 켜고 끄는 것이 응답 형식을 바꾼다")
	void carriesStreamFlag() {
		assertThat(client().body(request(Effort.LOW), false)).containsEntry("stream", false);
		assertThat(client().body(request(Effort.LOW), true)).containsEntry("stream", true);
	}
}
