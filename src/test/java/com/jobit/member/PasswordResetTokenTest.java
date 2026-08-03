package com.jobit.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PasswordResetTokenTest {

	private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 2, 12, 0, 0, 0,
			ZoneOffset.UTC);

	private final Member member = Member.local("a@b.com", "hash", "재헌");

	private PasswordResetToken tokenExpiringAt(OffsetDateTime expiresAt) {
		return new PasswordResetToken(member, "token-hash", expiresAt);
	}

	@Test
	@DisplayName("만료 전이고 사용 전이면 쓸 수 있다")
	void freshTokenIsUsable() {
		assertThat(tokenExpiringAt(NOW.plusMinutes(30)).isUsable(NOW)).isTrue();
	}

	@Test
	@DisplayName("만료 시각을 지나면 못 쓴다")
	void expiredTokenIsNotUsable() {
		PasswordResetToken token = tokenExpiringAt(NOW.minusSeconds(1));

		assertThat(token.isUsable(NOW)).isFalse();
	}

	@Test
	@DisplayName("한 번 쓰면 다시 못 쓴다 — 메일이 전달되며 재사용되는 것을 막는다")
	void usedTokenIsNotUsable() {
		PasswordResetToken token = tokenExpiringAt(NOW.plusMinutes(30));
		token.markUsed(NOW);

		assertThat(token.isUsable(NOW)).isFalse();
	}

	@Test
	@DisplayName("이미 사용한 토큰을 또 사용 처리하면 예외")
	void doubleUseThrows() {
		PasswordResetToken token = tokenExpiringAt(NOW.plusMinutes(30));
		token.markUsed(NOW);

		assertThatThrownBy(() -> token.markUsed(NOW)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("재발급 시 이전 토큰이 무효화된다")
	void invalidateMakesUnusable() {
		PasswordResetToken token = tokenExpiringAt(NOW.plusMinutes(30));
		token.invalidate(NOW);

		assertThat(token.isUsable(NOW)).isFalse();
		assertThat(token.getUsedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("이미 사용한 토큰을 무효화해도 사용 시각은 덮어쓰지 않는다")
	void invalidatePreservesOriginalUsedAt() {
		PasswordResetToken token = tokenExpiringAt(NOW.plusMinutes(30));
		token.markUsed(NOW);

		token.invalidate(NOW.plusMinutes(1));

		assertThat(token.getUsedAt()).isEqualTo(NOW);
	}
}
