package com.jobit.member;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * 메일 발송 수단이 정해지기 전까지 {@link ResetLinkSender} 자리를 채운다 (스펙 §7 미정).
 *
 * <p>개발 중에는 링크를 로그에 남겨 흐름을 직접 밟아볼 수 있게 한다. 대신 <b>운영 프로필에서는
 * 예외를 던진다</b> — 토큰이 로그로 새는 것이 더 위험하고, 메일이 안 가는 상태로 배포되면
 * 사용자는 "메일이 안 온다"만 겪을 뿐 원인을 알 수 없다.
 */
@Configuration
@Slf4j
public class ResetLinkSenderFallbackConfig {

	@Bean
	@ConditionalOnMissingBean(ResetLinkSender.class)
	public ResetLinkSender loggingResetLinkSender(Environment environment) {
		boolean production = environment.acceptsProfiles(Profiles.of("prod"));

		return (email, rawToken) -> {
			if (production) {
				throw new IllegalStateException(
						"ResetLinkSender 구현이 없습니다. 메일 발송 수단을 붙여야 재설정이 동작합니다.");
			}
			log.warn("[개발용] 메일 발송 대체 — {} 재설정 링크: /password/reset?token={}", email,
					rawToken);
		};
	}
}
