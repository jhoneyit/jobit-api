package com.jobit.member;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 비밀번호 재설정 토큰. 스펙 §3.7.
 *
 * <p>저장하는 것은 해시뿐이다. 원문은 메일로 나가고 우리 쪽에는 남지 않는다.
 */
@Entity
@Table(name = "password_reset_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PasswordResetToken {

	@Id
	@GeneratedValue
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Column(name = "token_hash", nullable = false, unique = true)
	private String tokenHash;

	@Column(name = "expires_at", nullable = false)
	private OffsetDateTime expiresAt;

	@Column(name = "used_at")
	private OffsetDateTime usedAt;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	PasswordResetToken(Member member, String tokenHash, OffsetDateTime expiresAt) {
		this.member = member;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
	}

	public boolean isUsable(OffsetDateTime now) {
		return usedAt == null && now.isBefore(expiresAt);
	}

	/**
	 * 사용 처리. 이미 쓴 토큰이면 거부한다 — 메일이 전달되며 재사용되는 것을 막는다 (스펙 §3.7).
	 */
	void markUsed(OffsetDateTime now) {
		if (usedAt != null) {
			throw new IllegalStateException("token already used");
		}
		this.usedAt = now;
	}

	/** 재발급 시 이전 토큰을 무효화한다. 이미 쓴 토큰은 그대로 둔다. */
	void invalidate(OffsetDateTime now) {
		if (usedAt == null) {
			this.usedAt = now;
		}
	}
}
