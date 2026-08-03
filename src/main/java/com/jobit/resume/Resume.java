package com.jobit.resume;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 이력서. 스펙 §3.3.
 *
 * <p>{@code ownerKey}는 익명 세션 키 또는 {@code member.id}를 담는다. 로그인 시 익명 키를
 * 회원 ID로 옮긴다 — 이관을 빠뜨리면 가입 직후 데이터가 사라진 것처럼 보인다 (스펙 §3.6).
 *
 * <p>{@code rawText}에는 이름·연락처가 그대로 들어간다. 암호화 저장이 필요하고, 로그에 남기지
 * 않는다 (스펙 §6). 그래서 {@code toString}을 만들지 않는다.
 */
@Entity
@Table(name = "resume")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Resume {

	@Id
	@GeneratedValue
	private UUID id;

	@Column(name = "owner_key", nullable = false)
	private String ownerKey;

	@Column(name = "raw_text", nullable = false)
	private String rawText;

	@JdbcTypeCode(SqlTypes.JSON)
	private String parsed;

	/** TTL. 회원 이력서에 걸 것인지는 미정 (스펙 §7). */
	@Column(name = "expires_at")
	private OffsetDateTime expiresAt;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	public Resume(String ownerKey, String rawText, String parsed, OffsetDateTime expiresAt) {
		this.ownerKey = ownerKey;
		this.rawText = rawText;
		this.parsed = parsed;
		this.expiresAt = expiresAt;
	}

	/** 익명 세션에서 만든 이력서를 회원 소유로 이관한다 (스펙 §3.6). */
	public void transferTo(String memberOwnerKey) {
		this.ownerKey = memberOwnerKey;
	}
}
