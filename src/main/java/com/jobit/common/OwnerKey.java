package com.jobit.common;

import java.util.UUID;

/**
 * 개인 자산의 소유자 키 (스펙 §3.3).
 *
 * <p>{@code resume.owner_key}, {@code jd_submission.owner_key}가 같은 값 공간을 쓴다. 회원 ID와
 * 익명 세션 키가 한 컬럼을 공유하므로 <b>접두사가 규약의 전부</b>다 — 접두사가 없으면 두
 * 네임스페이스가 충돌한다.
 *
 * <pre>
 * 로그인   user:&lt;user_id&gt;
 * 비로그인 anon:&lt;세션 쿠키&gt;
 * </pre>
 *
 * <p>{@code user:}의 ID는 <b>{@code jobit-front}의 {@code user} 테이블 ID</b>다. 이 서버의
 * {@code member} 테이블이 아니다 — 인증이 프론트에 있는 동안 회원 행은 그쪽에만 생긴다
 * (docs/architecture.md).
 *
 * <p><b>보안 주의.</b> 이 값은 HTTP로 프론트에서 넘어온다. 즉 호출자가 문자열을 마음대로 지어내면
 * 남의 이력을 읽을 수 있다. {@link #requireValid}는 <b>형식만</b> 본다 — 그 소유자를 사칭할 수
 * 있는지는 검사하지 못한다. 호출자 인증(서비스 토큰 등)이 별도로 있어야 하며, 그 방식은 아직
 * 미정이다 (docs/architecture.md 미결).
 */
public final class OwnerKey {

	public static final String USER_PREFIX = "user:";

	public static final String ANONYMOUS_PREFIX = "anon:";

	private OwnerKey() {
	}

	/** @param userId {@code jobit-front}의 user 테이블 ID */
	public static String forUser(String userId) {
		if (userId == null || userId.isBlank()) {
			throw new IllegalArgumentException("userId must not be blank");
		}
		return USER_PREFIX + userId;
	}

	public static String forUser(UUID userId) {
		return forUser(userId.toString());
	}

	public static String forAnonymous(String sessionKey) {
		if (sessionKey == null || sessionKey.isBlank()) {
			throw new IllegalArgumentException("sessionKey must not be blank");
		}
		return ANONYMOUS_PREFIX + sessionKey;
	}

	public static boolean isValid(String ownerKey) {
		if (ownerKey == null) {
			return false;
		}
		return hasBody(ownerKey, USER_PREFIX) || hasBody(ownerKey, ANONYMOUS_PREFIX);
	}

	public static boolean isAnonymous(String ownerKey) {
		return hasBody(ownerKey, ANONYMOUS_PREFIX);
	}

	/**
	 * 형식 검증. 컨트롤러 진입점에서 부른다.
	 *
	 * <p>접두사 없는 값을 그냥 통과시키면 안 된다 — 조회는 조용히 0건을 반환하므로 규약 위반이
	 * 드러나지 않고, 나중에 접두사 없는 행이 섞여 들어간다.
	 *
	 * @return 검증된 원래 값
	 */
	public static String requireValid(String ownerKey) {
		if (!isValid(ownerKey)) {
			throw new IllegalArgumentException(
					"owner_key must start with '" + USER_PREFIX + "' or '" + ANONYMOUS_PREFIX + "'");
		}
		return ownerKey;
	}

	private static boolean hasBody(String ownerKey, String prefix) {
		return ownerKey != null && ownerKey.startsWith(prefix)
				&& ownerKey.length() > prefix.length();
	}
}
