package com.jobit.common;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시간 의존성을 빈으로 뺀다.
 *
 * <p>토큰 만료·쿨다운처럼 시간이 조건인 로직을 {@code OffsetDateTime.now()} 직접 호출로 짜면
 * 테스트에서 만료를 재현할 방법이 {@code Thread.sleep} 뿐이다. {@link Clock#fixed}로 교체할 수
 * 있게 열어둔다.
 */
@Configuration
public class TimeConfig {

	@Bean
	@ConditionalOnMissingBean(Clock.class)
	public Clock clock() {
		return Clock.systemDefaultZone();
	}
}
