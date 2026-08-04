package com.jobit.llm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료된 레이트 리밋 창 정리.
 *
 * <p>조회는 PK({@code owner_key, window_start})로만 하므로 오래된 행이 남아 있어도 판정은 틀리지
 * 않는다. 다만 소유자 × 시간마다 한 행씩 무한정 쌓이므로 주기적으로 치운다.
 *
 * <p>지우는 기준을 2시간으로 둔 것은 <b>현재 창을 실수로 지우지 않기 위해서</b>다. 1시간으로
 * 잡으면 경계에서 방금 만든 창이 대상에 들어갈 수 있고, 그러면 카운터가 초기화되어 한도가 풀린다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitCleanup {

	private final LlmGuard guard;

	@Scheduled(fixedDelay = 3_600_000L, initialDelay = 600_000L)
	public void purge() {
		int removed = guard.purgeExpired();
		if (removed > 0) {
			log.debug("만료된 레이트 리밋 창 {}건 정리", removed);
		}
	}
}
