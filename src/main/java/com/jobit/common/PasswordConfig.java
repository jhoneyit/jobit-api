package com.jobit.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호 해싱 (스펙 §6).
 *
 * <p>BCrypt를 쓴다. SHA-256 같은 빠른 해시는 GPU로 대량 대입이 가능해 비밀번호 저장에 쓰지 않는다.
 */
@Configuration
public class PasswordConfig {

	/**
	 * strength 12. 기본값 10보다 한 단계 높였다 — 로그인·가입은 초당 수천 건이 오는 경로가
	 * 아니라서 수십 ms 추가 비용이 문제되지 않는다.
	 */
	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(12);
	}
}
