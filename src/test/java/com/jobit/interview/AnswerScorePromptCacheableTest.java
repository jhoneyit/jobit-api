package com.jobit.interview;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCountTokensParams;
import com.jobit.llm.LlmModelConfig;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 채점 시스템 프롬프트가 <b>캐시될 만큼 긴지</b> 확인한다.
 *
 * <p><b>왜 테스트로 두는가.</b> 최소 길이에 미달하면 API 가 오류를 내지 않는다 — 그냥
 * {@code cache_creation_input_tokens} 가 0 으로 조용히 지나간다. 프롬프트를 다듬다 짧아지면
 * 캐싱이 소리 없이 꺼지고, 비용이 두 배로 돌아가는데 아무도 모른다.
 *
 * <p><b>최소 길이는 모델마다 다르고 세대순으로 단조롭지도 않다</b> — Opus 5 는 512,
 * Opus 4.8 은 1,024, Opus 4.6 은 4,096 이다. 모델을 내리면 이 테스트가 먼저 깨져야 한다.
 *
 * <p>토큰 수는 추정하지 않고 {@code count_tokens} 로 센다 (과금되지 않는다). 다만 API 키가
 * 필요하므로 스모크와 같은 스위치로 켠다.
 */
@EnabledIfEnvironmentVariable(named = "JOBIT_LLM_SMOKE", matches = "(?i)1|true|on",
		disabledReason = "API 키가 필요하다. JOBIT_LLM_SMOKE=1 로 켠다.")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+",
		disabledReason = "ANTHROPIC_API_KEY 가 없다.")
class AnswerScorePromptCacheableTest {

	/**
	 * 모델별 최소 캐시 프리픽스.
	 *
	 * <p><b>세대순으로 단조롭지 않다.</b> 지금 프롬프트는 943 토큰이라 Opus 5(512)에서는
	 * 캐시되지만 <b>Opus 4.8(1,024)로 내리면 캐시되지 않는다</b> — 그래서 상수를 박지 않고
	 * 설정된 모델에서 끌어온다. 모델을 바꾸면 이 테스트가 먼저 깨진다.
	 */
	private static final java.util.Map<String, Long> MIN_CACHEABLE_TOKENS = java.util.Map.of(
			"claude-opus-5", 512L,
			"claude-fable-5", 512L,
			"claude-opus-4-8", 1_024L,
			"claude-sonnet-5", 1_024L,
			"claude-opus-4-7", 2_048L,
			"claude-opus-4-6", 4_096L,
			"claude-haiku-4-5", 4_096L);

	@Test
	@DisplayName("시스템 프롬프트가 최소 캐시 길이를 넘는다 — 미달하면 캐싱이 조용히 꺼진다")
	void systemPromptIsLongEnoughToCache() {
		String model = LlmModelConfig.of(com.jobit.llm.LlmFeature.ANSWER_SCORING).model();
		Long minimum = MIN_CACHEABLE_TOKENS.get(model);
		assertThat(minimum).as("최소 캐시 길이를 모르는 모델이다. 표를 갱신하라: %s", model).isNotNull();
		AnthropicClient client = AnthropicOkHttpClient.builder()
			.apiKey(System.getenv("ANTHROPIC_API_KEY"))
			.timeout(Duration.ofMinutes(1))
			.build();

		long tokens = client.messages()
			.countTokens(MessageCountTokensParams.builder()
				.model(model)
				.system(AnswerScorePrompts.SYSTEM)
				// count_tokens 는 메시지가 하나는 있어야 한다. 시스템 프롬프트 길이를 재는 것이
				// 목적이므로 최소한의 더미를 넣는다.
				.addUserMessage("x")
				.build())
			.inputTokens();

		System.out.printf("[cache] %s: 채점 시스템 프롬프트 ≈ %d 토큰 (최소 %d)%n", model, tokens,
				minimum);

		assertThat(tokens)
			.as("최소 길이에 미달하면 오류 없이 캐싱이 꺼진다 — 프롬프트를 줄였거나 모델을 내렸다면 "
					+ "여기서 멈춘다 (%s 의 최소는 %d)", model, minimum)
			.isGreaterThan(minimum);
	}
}
