package com.jobit.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 비용 계산 (스펙 §3.5).
 *
 * <p><b>지키는 성질이 뒤집혔다.</b> 유료 제공자 시절 이 테스트는 단가 곱셈이 맞는지를 봤다 —
 * 틀리면 비용 대시보드가 거짓말을 했다. 로컬 추론에서는 반대로 <b>0 이 아닌 금액이 나오는 것이
 * 버그다</b>: 지어낸 금액이 {@code llm_call_log} 에 쌓이면 {@link LlmGuard} 의 일일 상한이
 * 그 거짓 지출을 근거로 멀쩡한 요청을 막는다.
 */
class LlmPricingTest {

	@Test
	@DisplayName("로컬 모델은 얼마를 쓰든 0원이다")
	void localModelsCostNothing() {
		assertThat(LlmPricing.costUsd("qwen3:14b", 1_000_000, 1_000_000))
			.isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(LlmPricing.costUsd("qwen3-embedding:0.6b", 1_000_000, 0))
			.isEqualByComparingTo(BigDecimal.ZERO);
	}

	/**
	 * 유료 시절과 반대다. 그때는 모르는 모델을 최상위 단가로 잡았다 — 과소 계상이 위험했다.
	 * 이제 모르는 모델도 로컬이므로, 지어낸 단가가 일일 상한에 쌓이는 쪽이 위험하다.
	 */
	@Test
	@DisplayName("모르는 모델도 0원이다 — 다만 이름은 모르는 것으로 표시된다")
	void unknownModelIsFreeButFlagged() {
		assertThat(LlmPricing.costUsd("qwen3:someday-new", 1_000_000, 0))
			.isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(LlmPricing.isKnownModel("qwen3:someday-new"))
			.as("금액은 같아도 오타 감지는 남아 있어야 한다 — LlmCallRecorder 가 경고를 낸다")
			.isFalse();
	}

	@Test
	@DisplayName("기본 모델과 임베딩 모델은 단가표가 안다")
	void knowsConfiguredModels() {
		assertThat(LlmPricing.isKnownModel(LlmModelConfig.DEFAULT_MODEL)).isTrue();
		assertThat(LlmPricing.isKnownModel("qwen3-embedding:0.6b")).isTrue();
	}

	@Test
	@DisplayName("cost_usd 컬럼이 numeric(12,6)이라 6자리 스케일을 유지한다")
	void keepsColumnScale() {
		assertThat(LlmPricing.costUsd("qwen3:14b", 1, 0).scale()).isEqualTo(6);
	}
}
