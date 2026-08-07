package com.jobit.common;

import jakarta.annotation.PostConstruct;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * 호출자 인증 설정.
 *
 * <p><b>비밀키가 없으면 인증을 끈다.</b> 두 레포를 설정하지 않고도 로컬에서 바로 돌려 볼 수
 * 있어야 하기 때문이다. 다만 <b>운영에서 조용히 열려 있는 것이 가장 나쁘므로</b> 두 가지를 건다:
 *
 * <ul>
 *   <li>꺼진 채로 뜨면 부팅 로그에 경고를 크게 남긴다
 *   <li>{@code prod} 프로파일이면 비밀키 없이는 <b>아예 뜨지 않는다</b>
 * </ul>
 *
 * <p>비밀키는 {@code jobit-front} 의 {@code JOBIT_SERVICE_SECRET} 과 <b>같은 값</b>이어야 한다.
 * 한쪽만 설정하면 프론트가 서명 없이 부르거나(401) 서버가 검증하지 않는다.
 */
@Configuration
@Slf4j
public class ServiceAuthConfig {

	/** 이보다 짧은 키는 거절한다. `openssl rand -base64 32` 를 쓰라는 뜻이다. */
	private static final int MIN_SECRET_LENGTH = 32;

	private static final String[] PRODUCTION_PROFILES = { "prod", "production" };

	private final String secret;

	public ServiceAuthConfig(@Value("${jobit.auth.service-secret:}") String secret,
			Environment environment) {

		String trimmed = secret == null ? "" : secret.trim();
		this.secret = trimmed.isEmpty() ? null : trimmed;

		if (this.secret == null && isProduction(environment)) {
			throw new IllegalStateException("""
					jobit.auth.service-secret 이 없습니다. 운영에서는 호출자 인증을 끌 수 없습니다 \
					— owner_key 만 알면 남의 기록을 읽을 수 있습니다.""");
		}
		if (this.secret != null && this.secret.length() < MIN_SECRET_LENGTH) {
			throw new IllegalStateException(
					"jobit.auth.service-secret 이 너무 짧습니다 (최소 " + MIN_SECRET_LENGTH + "자). "
							+ "`openssl rand -base64 32` 로 만드세요.");
		}
	}

	/**
	 * 필터를 {@code /api/*} 에만 건다.
	 *
	 * <p>가장 앞 순서다 — 인증은 다른 무엇보다 먼저 판정되어야 한다. 정적 리소스와 에러
	 * 페이지는 대상이 아니다.
	 */
	@Bean
	public FilterRegistrationBean<ServiceAuthFilter> serviceAuthFilter(Clock clock) {
		FilterRegistrationBean<ServiceAuthFilter> registration = new FilterRegistrationBean<>(
				new ServiceAuthFilter(this, clock));
		registration.addUrlPatterns("/api/*");
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
		return registration;
	}

	/** {@code null} 이면 인증이 꺼진 것이다. */
	public String secret() {
		return secret;
	}

	public boolean enabled() {
		return secret != null;
	}

	@PostConstruct
	void announce() {
		if (enabled()) {
			log.info("호출자 인증 켜짐 — /api/** 는 X-Owner-Auth 서명을 요구합니다");
		}
		else {
			log.warn("""
					호출자 인증이 꺼져 있습니다 (jobit.auth.service-secret 미설정). \
					owner_key 만 알면 남의 기록을 읽을 수 있으므로 이 서버를 공개망에 노출하지 마세요.""");
		}
	}

	private static boolean isProduction(Environment environment) {
		for (String active : environment.getActiveProfiles()) {
			for (String production : PRODUCTION_PROFILES) {
				if (production.equalsIgnoreCase(active)) {
					return true;
				}
			}
		}
		return false;
	}
}
