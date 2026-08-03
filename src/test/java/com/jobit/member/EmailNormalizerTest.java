package com.jobit.member;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmailNormalizerTest {

	@Test
	@DisplayName("대소문자와 앞뒤 공백을 접는다 — 가입한 이메일로 로그인이 안 되는 상황을 막는다")
	void normalizesCaseAndWhitespace() {
		assertThat(EmailNormalizer.normalize("  JaeHeon@Example.COM  "))
			.isEqualTo("jaeheon@example.com");
	}

	@ParameterizedTest
	@ValueSource(strings = { "a@b.com", "jae.heon+tag@sub.example.co.kr", "x@y.io" })
	void acceptsValidShapes(String email) {
		assertThat(EmailNormalizer.isValid(email)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "no-at-sign", "@example.com", "a@", "a@b", "a b@c.com",
			"a@b .com" })
	void rejectsInvalidShapes(String email) {
		assertThat(EmailNormalizer.isValid(email)).isFalse();
	}

	@Test
	void rejectsNull() {
		assertThat(EmailNormalizer.isValid(null)).isFalse();
	}

	@Test
	@DisplayName("254자를 넘으면 거부한다")
	void rejectsTooLong() {
		String local = "a".repeat(250);
		assertThat(EmailNormalizer.isValid(local + "@b.com")).isFalse();
	}
}
