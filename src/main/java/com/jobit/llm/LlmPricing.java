package com.jobit.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 토큰 단가와 호출 1건의 비용 계산 (스펙 §3.5).
 *
 * <p><b>Ollama 로 옮긴 뒤 이 표는 전부 0 이다.</b> 로컬 추론에는 토큰당 요금이 없다 — 나가는 것은
 * 돈이 아니라 시간과 전력이고, 둘 다 토큰 수에 비례해 청구되지 않는다. 0 을 채워 넣은 표를 남긴
 * 이유는 두 가지다.
 *
 * <ol>
 *   <li><b>{@code llm_call_log.cost_usd} 가 {@code not null} 이다.</b> 지난 기록에는 Anthropic·
 *       OpenAI 시절의 실제 지출이 들어 있고, 그 컬럼을 지우면 그 이력도 함께 사라진다.
 *   <li><b>{@link LlmGuard} 의 전역 일일 상한이 이 값을 읽는다.</b> 계산이 0 을 내면 그 방어는
 *       자연히 발동하지 않는다 — 스위치를 따로 끄지 않아도 의미가 맞는다.
 * </ol>
 *
 * <p><b>{@link #DEFAULT_RATE} 가 뒤집혔다.</b> 예전에는 모르는 모델을 최상위 단가로 잡았다 —
 * 과소 계상보다 과대 계상이 안전했기 때문이다. 이제는 모르는 모델도 로컬 모델이므로 0 이 맞고,
 * 반대로 <b>과대 계상이 위험하다</b>: 지어낸 금액이 일일 상한에 쌓여 멀쩡한 요청을 막게 된다.
 *
 * <p>유료 제공자를 다시 붙이면 여기에 그 모델의 단가를 넣는다. 그때는 캐시 토큰 단가
 * (읽기 0.1배·쓰기 1.25배)도 함께 돌아와야 한다 — Ollama 에는 그 개념이 없어 계산에서 뺐다.
 */
public final class LlmPricing {

	private static final BigDecimal PER_MILLION = new BigDecimal("1000000");

	private static final Rate FREE = new Rate(BigDecimal.ZERO, BigDecimal.ZERO);

	/**
	 * USD per 1M tokens. 로컬 모델이라 전부 0 이다.
	 *
	 * <p>표를 비워 두지 않는 이유는 {@link #isKnownModel} 이다 — 오타로 다른 모델 이름이 흘러가면
	 * 경고가 뜨는 편이 낫다. 값이 아니라 <b>이름이 맞는지</b>를 보는 표로 성격이 바뀌었다.
	 */
	private static final Map<String, Rate> RATES = Map.of("qwen3:14b", FREE, "qwen3:8b", FREE,
			"qwen3:30b-a3b", FREE, "qwen3:32b", FREE, "qwen3-embedding:0.6b", FREE);

	/** 모르는 모델도 로컬이라 0 이다 — 위 클래스 주석의 "뒤집혔다" 항목 참고. */
	private static final Rate DEFAULT_RATE = FREE;

	private LlmPricing() {
	}

	/**
	 * 호출 1건의 USD 비용. {@code llm_call_log.cost_usd} 는 {@code numeric(12,6)} 이라 6자리에서
	 * 반올림한다.
	 */
	public static BigDecimal costUsd(String model, long inputTokens, long outputTokens) {
		Rate rate = RATES.getOrDefault(model, DEFAULT_RATE);

		return perMillion(rate.input(), inputTokens).add(perMillion(rate.output(), outputTokens))
			.setScale(6, RoundingMode.HALF_UP);
	}

	public static boolean isKnownModel(String model) {
		return RATES.containsKey(model);
	}

	private static BigDecimal perMillion(BigDecimal ratePerMillion, long tokens) {
		return ratePerMillion.multiply(BigDecimal.valueOf(tokens))
			.divide(PER_MILLION, 12, RoundingMode.HALF_UP);
	}

	private record Rate(BigDecimal input, BigDecimal output) {
	}
}
