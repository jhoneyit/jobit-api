package com.jobit.member;

import com.jobit.common.OwnerKey;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원. 스펙 §3.6.
 *
 * <p>가입 수단은 두 가지다.
 * <ul>
 *   <li>{@code LOCAL} — 이메일 + 비밀번호. {@code providerUid}에 정규화한 이메일이 들어가므로
 *       {@code unique (provider, provider_uid)} 하나로 이메일 중복이 막힌다.</li>
 *   <li>{@code GITHUB} — OAuth. 비밀번호를 보관하지 않는다.</li>
 * </ul>
 *
 * <p><b>{@code passwordHash}는 게터를 열지 않는다.</b> 값을 꺼내 쓸 일은 검증뿐이고,
 * 게터가 있으면 로그나 응답 DTO에 실려 나갈 길이 생긴다 (스펙 §6).
 * 검증은 {@link #hasPassword()}와 서비스 계층의 인코더가 맡는다.
 */
@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

	public enum Provider {
		LOCAL, GITHUB
	}

	@Id
	@GeneratedValue
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Provider provider;

	@Column(name = "provider_uid", nullable = false)
	private String providerUid;

	private String email;

	@Getter(AccessLevel.NONE)
	@Column(name = "password_hash")
	private String passwordHash;

	@Column(nullable = false)
	private String nickname;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	private Member(Provider provider, String providerUid, String email, String passwordHash,
			String nickname) {
		this.provider = provider;
		this.providerUid = providerUid;
		this.email = email;
		this.passwordHash = passwordHash;
		this.nickname = nickname;
	}

	/**
	 * 일반 회원가입.
	 *
	 * @param normalizedEmail {@link EmailNormalizer#normalize}를 거친 값
	 * @param passwordHash    이미 해싱된 값. 평문을 받지 않는다.
	 */
	public static Member local(String normalizedEmail, String passwordHash, String nickname) {
		return new Member(Provider.LOCAL, normalizedEmail, normalizedEmail, passwordHash, nickname);
	}

	public static Member oauth(Provider provider, String providerUid, String email,
			String nickname) {
		if (provider == Provider.LOCAL) {
			throw new IllegalArgumentException("use Member.local() for local signup");
		}
		return new Member(provider, providerUid, email, null, nickname);
	}

	public boolean hasPassword() {
		return passwordHash != null;
	}

	/**
	 * 비밀번호 검증. 해시를 밖으로 내보내지 않기 위해 비교를 엔티티 안에서 한다.
	 *
	 * @param matcher 평문과 해시를 비교하는 함수 (인코더 주입)
	 */
	public boolean passwordMatches(String rawPassword, PasswordMatcher matcher) {
		return hasPassword() && matcher.matches(rawPassword, passwordHash);
	}

	public void changePassword(String newPasswordHash) {
		if (provider != Provider.LOCAL) {
			throw new IllegalStateException("only LOCAL members have a password");
		}
		this.passwordHash = newPasswordHash;
	}

	/**
	 * 이력서 등의 {@code owner_key}로 쓰이는 값. 익명 세션 키와 같은 컬럼을 공유하므로 접두사가
	 * 붙는다 (스펙 §3.6, {@link OwnerKey}).
	 *
	 * <p>인증이 {@code jobit-front}에 있는 동안 실제 소유자 키는 그쪽 user 테이블 ID로 만들어진다.
	 * 이 메서드는 이 서버가 회원을 직접 다루게 될 때를 위한 것이다.
	 */
	public String ownerKey() {
		return OwnerKey.forUser(id);
	}

	@FunctionalInterface
	public interface PasswordMatcher {

		boolean matches(CharSequence rawPassword, String encodedPassword);
	}
}
