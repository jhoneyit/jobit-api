package com.jobit.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 비용 계산 (스펙 §3.5).
 *
 * <p>이 값이 틀리면 비용 대시보드가 조용히 거짓말을 한다 — 그걸 보고 모델 티어를 정하므로
 * 틀린 방향으로 결정하게 된다.
 */
class LlmPricingTest {

	@Test
	@DisplayName("입력·출력 단가를 따로 곱한다")
	void chargesInputAndOutputSeparately() {
		// opus-5: 입력 $5/M, 출력 $25/M → 1M + 1M = $30
		BigDecimal cost = LlmPricing.costUsd("claude-opus-5", 1_000_000, 1_000_000, 0, 0);

		assertThat(cost).isEqualByComparingTo("30.0");
	}

	@Test
	@DisplayName("캐시 읽기는 입력의 0.1배 — 입력 토큰에 합산하지 않는다")
	void chargesCacheReadAtReducedRate() {
		BigDecimal cost = LlmPricing.costUsd("claude-opus-5", 0, 0, 1_000_000, 0);

		assertThat(cost).isEqualByComparingTo("0.5");
	}

	@Test
	@DisplayName("캐시 생성은 입력의 1.25배")
	void chargesCacheWriteAtPremium() {
		BigDecimal cost = LlmPricing.costUsd("claude-opus-5", 0, 0, 0, 1_000_000);

		assertThat(cost).isEqualByComparingTo("6.25");
	}

	@Test
	@DisplayName("모르는 모델은 0원이 아니라 최상위 단가로 잡는다")
	void unknownModelFallsBackToTopRate() {
		BigDecimal cost = LlmPricing.costUsd("claude-something-new", 1_000_000, 0, 0, 0);

		assertThat(cost).isEqualByComparingTo("5.0");
		assertThat(LlmPricing.isKnownModel("claude-something-new")).isFalse();
	}

	@Test
	@DisplayName("cost_usd 컬럼이 numeric(12,6)이라 6자리로 반올림한다")
	void roundsToColumnScale() {
		BigDecimal cost = LlmPricing.costUsd("claude-opus-5", 1, 0, 0, 0);

		assertThat(cost.scale()).isEqualTo(6);
	}

	@Test
	@DisplayName("호출이 없으면 0원")
	void zeroTokensCostNothing() {
		assertThat(LlmPricing.costUsd("claude-opus-5", 0, 0, 0, 0))
			.isEqualByComparingTo(BigDecimal.ZERO);
	}
}
