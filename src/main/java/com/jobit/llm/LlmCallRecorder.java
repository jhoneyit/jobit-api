package com.jobit.llm;

import com.anthropic.models.messages.Usage;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * LLM 호출 비용 기록 (스펙 §3.5).
 *
 * <p><b>기록 실패가 호출을 실패시키지 않는다.</b> 비용 로그는 관측용이라, 여기서 터져 사용자
 * 요청까지 죽이면 손해가 크다. 대신 경고를 남긴다.
 *
 * <p>{@code REQUIRES_NEW}인 이유: 호출부 트랜잭션이 나중에 롤백되어도 <b>돈은 이미 나갔으므로</b>
 * 로그는 남아야 한다. 같은 트랜잭션에 묶으면 실패한 요청의 비용이 통째로 사라진다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmCallRecorder {

	private final LlmCallLogRepository repository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(LlmFeature feature, String model, Usage usage, boolean cacheHit,
			long latencyMs) {
		try {
			long input = usage.inputTokens();
			long output = usage.outputTokens();
			long cacheRead = usage.cacheReadInputTokens().orElse(0L);
			long cacheCreation = usage.cacheCreationInputTokens().orElse(0L);

			BigDecimal cost = LlmPricing.costUsd(model, input, output, cacheRead, cacheCreation);
			if (!LlmPricing.isKnownModel(model)) {
				log.warn("단가표에 없는 모델입니다. 기본 단가로 계산합니다: {}", model);
			}

			repository.save(new LlmCallLog(feature.name(), model, (int) input, (int) output,
					(int) cacheRead, (int) cacheCreation, cost, cacheHit, (int) latencyMs));

			log.info("LLM {} model={} in={} out={} cost=${} {}ms", feature, model, input, output,
					cost, latencyMs);
		}
		catch (RuntimeException ex) {
			log.warn("LLM 비용 로깅 실패 (요청은 계속 진행): feature={} model={}", feature, model, ex);
		}
	}
}
