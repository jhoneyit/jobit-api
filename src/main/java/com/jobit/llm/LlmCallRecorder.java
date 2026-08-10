package com.jobit.llm;

import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * LLM 호출 기록 (스펙 §3.5).
 *
 * <p><b>기록 실패가 호출을 실패시키지 않는다.</b> 이 로그는 관측용이라, 여기서 터져 사용자 요청까지
 * 죽이면 손해가 크다. 대신 경고를 남긴다.
 *
 * <p>{@code REQUIRES_NEW}인 이유: 호출부 트랜잭션이 나중에 롤백되어도 <b>추론은 이미 일어났으므로</b>
 * 로그는 남아야 한다. 같은 트랜잭션에 묶으면 실패한 요청의 소비가 통째로 사라진다.
 *
 * <p><b>Ollama 로 옮긴 뒤 이 장부의 쓰임이 달라졌다.</b> 비용은 이제 늘 0 이고
 * ({@link LlmPricing} 참고), 남는 값은 <b>토큰 수와 지연</b>이다. 어느 기능이 느린지 — 로컬에서는
 * 그게 곧 사용자 대기 시간이다 — 를 보는 장부로 읽으면 된다. {@code cost_usd} 컬럼은 지우지 않았다:
 * 지난 기록에 실제 지출이 들어 있고, 유료 제공자를 다시 붙일 때 그대로 쓰인다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmCallRecorder {

	private final LlmCallLogRepository repository;

	/**
	 * <p><b>캐시 토큰 인자가 사라졌다.</b> Anthropic 의 {@code cache_read_input_tokens} /
	 * {@code cache_creation_input_tokens} 에 해당하는 것이 Ollama 에 없다 — KV 캐시 재사용은
	 * 일어나지만 응답에 드러나지 않는다. 컬럼은 남겨 두고 0 을 쓴다 (지난 기록의 값은 그대로다).
	 *
	 * @param cacheHit LLM 을 부르지 않고 우리 캐시로 처리했는지. 토큰 캐시와는 다른 이야기다
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(LlmFeature feature, String model, long input, long output, boolean cacheHit,
			long latencyMs) {
		try {
			BigDecimal cost = LlmPricing.costUsd(model, input, output);
			if (!LlmPricing.isKnownModel(model)) {
				// 금액은 어차피 0 이라 틀릴 게 없다. 여기서 잡고 싶은 것은 모델 이름 오타다 —
				// 설정에 없는 이름을 적으면 Ollama 가 404 를 주는데, 그 전에 알려 준다.
				log.warn("LlmPricing 이 모르는 모델입니다. 이름을 확인하세요: {}", model);
			}

			repository.save(new LlmCallLog(feature.name(), model, (int) input, (int) output, 0, 0,
					cost, cacheHit, (int) latencyMs));

			log.info("LLM {} model={} in={} out={} {}ms", feature, model, input, output, latencyMs);
		}
		catch (RuntimeException ex) {
			log.warn("LLM 호출 로깅 실패 (요청은 계속 진행): feature={} model={}", feature, model, ex);
		}
	}
}
