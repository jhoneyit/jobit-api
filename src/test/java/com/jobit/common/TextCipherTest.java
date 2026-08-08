package com.jobit.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 이력서 원문 암호화의 계약 (스펙 §6).
 *
 * <p>여기서 고정하는 것은 알고리즘이 아니라 <b>실패 방식</b>이다. 암호화는 잘 동작할 때 아무도
 * 보지 않고, 잘못될 때도 조용한 경우가 많다 — 평문으로 폴백하거나, 변조된 값을 그럴듯한
 * 평문으로 복호화하거나, 같은 IV 를 재사용하는 것 전부 눈에 띄지 않는다.
 */
class TextCipherTest {

	/** {@code openssl rand -base64 32} 로 만든 형태. 테스트 전용 값이다. */
	private static final String KEY = Base64.getEncoder()
		.encodeToString("0123456789abcdef0123456789abcdef".getBytes());

	private static TextCipher withKey(String key) {
		return new TextCipher(key, new MockEnvironment());
	}

	@Nested
	@DisplayName("키가 있으면")
	class WithKey {

		private final TextCipher cipher = withKey(KEY);

		@Test
		@DisplayName("암호화한 값을 그대로 복원한다")
		void roundTrip() {
			String plaintext = "김재헌 / 010-1234-5678\n결제 서버 개발, 응답시간 300ms → 80ms";

			assertThat(cipher.decrypt(cipher.encrypt(plaintext))).isEqualTo(plaintext);
		}

		@Test
		@DisplayName("암호문에 원문이 남지 않는다")
		void ciphertextHidesPlaintext() {
			String encrypted = cipher.encrypt("결제 서버 개발");

			assertThat(encrypted).doesNotContain("결제 서버 개발").startsWith("v1.");
		}

		@Test
		@DisplayName("같은 평문도 매번 다르게 암호화된다 — IV 재사용은 GCM 에서 치명적이다")
		void ivIsNeverReused() {
			String plaintext = "같은 문장";

			assertThat(cipher.encrypt(plaintext)).isNotEqualTo(cipher.encrypt(plaintext));
		}

		@Test
		@DisplayName("변조된 값은 조용히 이상한 평문을 내지 않고 예외가 된다")
		void tamperingIsDetected() {
			String encrypted = cipher.encrypt("원본 문장");
			// 마지막 문자를 바꾼다 — 인증 태그가 걸려야 한다.
			String tampered = encrypted.substring(0, encrypted.length() - 1)
					+ (encrypted.endsWith("A") ? "B" : "A");

			assertThatThrownBy(() -> cipher.decrypt(tampered))
				.isInstanceOf(IllegalStateException.class);
		}

		@Test
		@DisplayName("다른 키로 암호화된 값은 복호화되지 않는다")
		void wrongKeyIsRejected() {
			String otherKey = Base64.getEncoder()
				.encodeToString("fedcba9876543210fedcba9876543210".getBytes());
			String encrypted = withKey(otherKey).encrypt("원본 문장");

			assertThatThrownBy(() -> cipher.decrypt(encrypted))
				.isInstanceOf(IllegalStateException.class);
		}

		@Test
		@DisplayName("평문이 섞여 들어오면 그대로 반환하지 않고 거부한다")
		void plaintextIsNotPassedThrough() {
			assertThatThrownBy(() -> cipher.decrypt("암호화되지 않은 문장"))
				.isInstanceOf(IllegalStateException.class);
		}
	}

	@Nested
	@DisplayName("키가 없으면")
	class WithoutKey {

		private final TextCipher cipher = withKey("");

		@Test
		@DisplayName("평문으로 폴백하지 않고 예외를 던진다 — 되돌릴 수 없는 실패는 미리 막는다")
		void refusesInsteadOfStoringPlaintext() {
			assertThatThrownBy(() -> cipher.encrypt("이력서"))
				.isInstanceOf(TextCipher.NotConfiguredException.class);
		}

		@Test
		@DisplayName("enabled() 로 호출 전에 확인할 수 있다")
		void reportsDisabled() {
			assertThat(cipher.enabled()).isFalse();
		}
	}

	@Nested
	@DisplayName("키 형식이 틀리면 부팅 시점에 막는다")
	class BadKey {

		@Test
		@DisplayName("base64 가 아니면 거부한다")
		void rejectsNonBase64() {
			assertThatThrownBy(() -> withKey("!!! not base64 !!!"))
				.isInstanceOf(IllegalStateException.class);
		}

		@Test
		@DisplayName("32바이트가 아니면 거부한다 — 조용히 AES-128 로 떨어지지 않게")
		void rejectsWrongLength() {
			String tooShort = Base64.getEncoder().encodeToString("short".getBytes());

			assertThatThrownBy(() -> withKey(tooShort)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("32");
		}
	}

	@Nested
	@DisplayName("운영 프로파일에서는")
	class Production {

		@Test
		@DisplayName("키가 없으면 앱이 아예 뜨지 않는다")
		void refusesToStartWithoutKey() {
			MockEnvironment prod = new MockEnvironment();
			prod.setActiveProfiles("prod");

			assertThatThrownBy(() -> new TextCipher("", prod))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("평문");
		}
	}
}
