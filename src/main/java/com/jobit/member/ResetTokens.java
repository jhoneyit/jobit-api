package com.jobit.member;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 재설정 토큰 생성과 해싱 (스펙 §3.7).
 *
 * <p><b>SHA-256을 쓴다 — 비밀번호와 반대다.</b> 비밀번호는 사람이 만든 저엔트로피 문자열이라
 * BCrypt 같은 느린 해시가 필요하지만, 이 토큰은 256비트 난수라 대입이 불가능하다.
 * 오히려 BCrypt는 salt 때문에 해시로 조회할 수 없어 못 쓴다.
 */
final class ResetTokens {

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final int TOKEN_BYTES = 32;

	private ResetTokens() {
	}

	/** URL에 그대로 실을 수 있는 형태. 이 값은 메일로만 나가고 저장하지 않는다. */
	static String generate() {
		byte[] bytes = new byte[TOKEN_BYTES];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String hash(String token) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(token.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 not available", ex);
		}
	}
}
