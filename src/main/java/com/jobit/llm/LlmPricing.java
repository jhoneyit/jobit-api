package com.jobit.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 토큰 단가와 호출 1건의 비용 계산 (스펙 §3.5).
 *
 * <p>USD per 1M tokens. <b>모델을 바꾸면 여기도 같이 바꾼다</b> — 단가가 빠지면 조용히 0원으로
 * 기록되어 비용 대시보드가 거짓말을 한다.
 *
 * <p>프롬프트 캐시는 읽기와 생성의 단가가 다르다. 캐시 읽기는 입력의 0.1배, 캐시 생성은 1.25배다.
 * 입력 토큰에 합산하면 비용이 틀어지므로 따로 계산한다.
 */
public final class LlmPricing {

	private static final BigDecimal PER_MILLION = new BigDecimal("1000000");

	/** 캐시 읽기 배율. */
	private static final BigDecimal CACHE_READ_RATIO = new BigDecimal("0.1");

	/** 캐시 생성 배율 (5분 TTL 기준). */
	private static final BigDecimal CACHE_WRITE_RATIO = new BigDecimal("1.25");

	private static final Map<String, Rate> RATES = Map.of(
			"claude-opus-5", new Rate(new BigDecimal("5.0"), new BigDecimal("25.0")),
			"claude-sonnet-5", new Rate(new BigDecimal("3.0"), new BigDecimal("15.0")),
			"claude-haiku-4-5", new Rate(new BigDecimal("1.0"), new BigDecimal("5.0")));

	/** 모르는 모델은 최상위 단가로 잡는다 — 과소 계상보다 과대 계상이 안전하다. */
	private static final Rate DEFAULT_RATE = new Rate(new BigDecimal("5.0"), new BigDecimal("25.0"));

	private LlmPricing() {
	}

	/**
	 * 호출 1건의 USD 비용. {@code llm_call_log.cost_usd}는 {@code numeric(12,6)}이라 6자리에서 반올림한다.
	 */
	public static BigDecimal costUsd(String model, long inputTokens, long outputTokens,
			long cacheReadTokens, long cacheCreationTokens) {
		Rate rate = RATES.getOrDefault(model, DEFAULT_RATE);

		BigDecimal cost = perMillion(rate.input(), inputTokens)
			.add(perMillion(rate.output(), outputTokens))
			.add(perMillion(rate.input().multiply(CACHE_READ_RATIO), cacheReadTokens))
			.add(perMillion(rate.input().multiply(CACHE_WRITE_RATIO), cacheCreationTokens));

		return cost.setScale(6, RoundingMode.HALF_UP);
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
