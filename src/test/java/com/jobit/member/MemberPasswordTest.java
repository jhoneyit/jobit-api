package com.jobit.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class MemberPasswordTest {

	// 테스트에서는 강도를 낮춘다. 검증 대상은 동작이지 비용이 아니다.
	private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

	@Test
	@DisplayName("올바른 비밀번호만 통과한다")
	void matchesOnlyCorrectPassword() {
		Member member = Member.local("a@b.com", encoder.encode("correct-horse"), "재헌");

		assertThat(member.passwordMatches("correct-horse", encoder::matches)).isTrue();
		assertThat(member.passwordMatches("wrong", encoder::matches)).isFalse();
	}

	@Test
	@DisplayName("BCrypt는 같은 평문도 매번 다른 해시를 만든다 — salt가 붙기 때문")
	void hashesAreSalted() {
		assertThat(encoder.encode("same-password")).isNotEqualTo(encoder.encode("same-password"));
	}

	@Test
	@DisplayName("OAuth 회원은 비밀번호가 없고, 어떤 값으로도 인증되지 않는다")
	void oauthMemberHasNoPassword() {
		Member member = Member.oauth(Member.Provider.GITHUB, "gh-123", "a@b.com", "재헌");

		assertThat(member.hasPassword()).isFalse();
		assertThat(member.passwordMatches("anything", encoder::matches)).isFalse();
		assertThat(member.passwordMatches(null, encoder::matches)).isFalse();
	}

	@Test
	@DisplayName("OAuth 회원에게 비밀번호를 설정할 수 없다")
	void oauthMemberRejectsPasswordChange() {
		Member member = Member.oauth(Member.Provider.GITHUB, "gh-123", "a@b.com", "재헌");

		assertThatThrownBy(() -> member.changePassword(encoder.encode("x")))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("Member.oauth로 LOCAL 회원을 만들 수 없다 — 비밀번호 없는 로컬 계정이 생긴다")
	void oauthFactoryRejectsLocalProvider() {
		assertThatThrownBy(() -> Member.oauth(Member.Provider.LOCAL, "a@b.com", "a@b.com", "재헌"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("LOCAL 회원의 provider_uid는 이메일이다 — 유니크 제약이 이메일 중복을 막는다")
	void localMemberUsesEmailAsProviderUid() {
		Member member = Member.local("a@b.com", encoder.encode("password1"), "재헌");

		assertThat(member.getProviderUid()).isEqualTo("a@b.com");
		assertThat(member.getEmail()).isEqualTo("a@b.com");
		assertThat(member.getProvider()).isEqualTo(Member.Provider.LOCAL);
	}
}
