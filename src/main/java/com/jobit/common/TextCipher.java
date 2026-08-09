package com.jobit.common;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 개인정보 원문의 저장용 암호화 (스펙 §6, 작업 원칙 "이력서 원문은 암호화 저장").
 *
 * <p>지금 쓰는 곳은 {@code resume.raw_text} 하나다. 이력서 본문에는 이름·연락처·회사명이 그대로
 * 들어가므로 DB 덤프가 통째로 새도 원문이 읽히지 않아야 한다.
 *
 * <p><b>AES-256-GCM 이다.</b> CBC 가 아닌 이유는 인증 태그다 — GCM 은 복호화 시점에 변조를
 * 검출하므로, 누군가 컬럼 값을 바꿔치기하면 조용히 이상한 평문이 나오는 대신 예외가 난다.
 * 개인정보를 다루는 자리에서는 "틀린 값을 그럴듯하게 반환"하는 실패 모드가 가장 나쁘다.
 *
 * <p><b>IV 는 값마다 새로 뽑는다.</b> GCM 에서 같은 키로 IV 를 재사용하면 평문을 복원할 수 있는
 * 치명적 실패가 된다. 그래서 IV 를 결과 앞에 붙여 저장한다 — IV 는 비밀이 아니고, 유일하기만
 * 하면 된다.
 *
 * <pre>
 * 저장 형식: v1.&lt;base64url( IV(12B) || 암호문 || 인증태그(16B) )&gt;
 * </pre>
 *
 * <p>앞의 {@code v1.} 은 <b>키 회전과 알고리즘 교체를 위한 자리</b>다. 이게 없으면 나중에
 * 방식을 바꿀 때 기존 행이 어느 방식으로 암호화됐는지 알 방법이 없어, 전수 재암호화 외에
 * 선택지가 없어진다.
 *
 * <p><b>키가 없으면 암호화하지 않고 거부한다.</b> {@code ServiceAuthConfig} 는 키가 없으면 인증을
 * 끄고 뜨지만, 이쪽은 같은 선택을 하지 않는다 — 인증이 꺼진 것은 로그를 보면 알 수 있지만,
 * 평문으로 저장된 이력서는 나중에 되돌릴 방법이 없다. 사고가 난 뒤에 고칠 수 없는 종류의
 * 실패라 처음부터 막는다.
 */
@Component
@Slf4j
public class TextCipher {

	/** 현재 형식. 방식을 바꾸면 올리고, 복호화는 옛 버전도 계속 받아 준다. */
	private static final String VERSION_PREFIX = "v1.";

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";

	/** GCM 권장 IV 길이. 12바이트가 아니면 내부적으로 해싱이 한 번 더 들어간다. */
	private static final int IV_LENGTH = 12;

	/** 인증 태그 길이 (비트). */
	private static final int TAG_LENGTH_BITS = 128;

	/** AES-256 이므로 32바이트. 짧은 키를 받아 조용히 AES-128 로 떨어지지 않게 강제한다. */
	private static final int KEY_LENGTH = 32;

	private static final String[] PRODUCTION_PROFILES = { "prod", "production" };

	private final SecureRandom random = new SecureRandom();

	/** {@code null} 이면 암호화가 설정되지 않은 것이다. */
	private final SecretKeySpec key;

	public TextCipher(@Value("${jobit.resume.encryption-key:}") String base64Key,
			Environment environment) {

		String trimmed = base64Key == null ? "" : base64Key.trim();
		this.key = trimmed.isEmpty() ? null : toKey(trimmed);

		if (this.key == null) {
			if (isProduction(environment)) {
				throw new IllegalStateException("""
						jobit.resume.encryption-key 가 없습니다. 운영에서는 이력서 원문을 \
						평문으로 저장할 수 없습니다.""");
			}
			log.warn("""
					이력서 암호화 키가 없습니다 (jobit.resume.encryption-key 미설정). \
					이력서 업로드는 거부됩니다 — `openssl rand -base64 32` 로 만들어 .env 에 넣으세요.""");
		}
	}

	public boolean enabled() {
		return key != null;
	}

	/**
	 * @return {@code v1.} 로 시작하는 저장용 문자열
	 * @throws NotConfiguredException 키가 없을 때. <b>평문으로 폴백하지 않는다.</b>
	 */
	public String encrypt(String plaintext) {
		requireConfigured();
		if (plaintext == null) {
			throw new IllegalArgumentException("plaintext must not be null");
		}

		byte[] iv = new byte[IV_LENGTH];
		random.nextBytes(iv);

		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
			byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

			byte[] combined = new byte[iv.length + sealed.length];
			System.arraycopy(iv, 0, combined, 0, iv.length);
			System.arraycopy(sealed, 0, combined, iv.length, sealed.length);

			return VERSION_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
		}
		catch (GeneralSecurityException ex) {
			// 키 길이는 생성자에서 이미 검증했으므로 여기 오는 것은 JVM 설정 문제다.
			throw new IllegalStateException("이력서 암호화에 실패했습니다.", ex);
		}
	}

	/**
	 * @throws NotConfiguredException 키가 없을 때
	 * @throws IllegalStateException  형식이 틀리거나 <b>인증 태그 검증에 실패했을 때</b>
	 *                                (= 값이 변조되었거나 다른 키로 암호화된 것)
	 */
	public String decrypt(String stored) {
		requireConfigured();
		if (stored == null || !stored.startsWith(VERSION_PREFIX)) {
			// 평문이 섞여 있다는 뜻이다. 그대로 반환하면 암호화 약속이 조용히 깨진다.
			throw new IllegalStateException("암호화되지 않았거나 형식이 다른 값입니다.");
		}

		byte[] combined = Base64.getUrlDecoder().decode(stored.substring(VERSION_PREFIX.length()));
		if (combined.length <= IV_LENGTH) {
			throw new IllegalStateException("암호문이 너무 짧습니다.");
		}

		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key,
					new GCMParameterSpec(TAG_LENGTH_BITS, combined, 0, IV_LENGTH));
			byte[] plain = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
			return new String(plain, StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException ex) {
			// AEADBadTagException 이 여기 걸린다. 원문을 로그에 남기지 않는다.
			throw new IllegalStateException("이력서 원문을 복호화하지 못했습니다.", ex);
		}
	}

	private void requireConfigured() {
		if (key == null) {
			throw new NotConfiguredException();
		}
	}

	private static SecretKeySpec toKey(String base64Key) {
		byte[] raw;
		try {
			raw = Base64.getDecoder().decode(base64Key);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException(
					"jobit.resume.encryption-key 가 base64 가 아닙니다. `openssl rand -base64 32` 로 만드세요.",
					ex);
		}
		if (raw.length != KEY_LENGTH) {
			throw new IllegalStateException("jobit.resume.encryption-key 는 %d바이트여야 합니다 (받은 값: %d바이트). "
					.formatted(KEY_LENGTH, raw.length) + "`openssl rand -base64 32` 로 만드세요.");
		}
		return new SecretKeySpec(raw, "AES");
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

	/** 키가 설정되지 않은 상태. 재시도해도 소용없으므로 5xx 로 매핑된다. */
	public static class NotConfiguredException extends IllegalStateException {

		public NotConfiguredException() {
			super("이력서 암호화 키가 설정되지 않았습니다. jobit.resume.encryption-key 를 넣어야 합니다.");
		}
	}
}
