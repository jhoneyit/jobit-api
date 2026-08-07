package com.jobit.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 호출자 인증 — {@code owner_key} 에 붙은 HMAC 서명을 검증한다 (docs/architecture.md 미결 해소).
 *
 * <p><b>무엇을 막는가.</b> 이 서버는 {@code owner_key} 를 그대로 믿고 소유자를 판단한다.
 * 즉 그 값을 지어낼 수 있으면 남의 이력·면접 기록을 읽을 수 있다. {@code OwnerKey.requireValid}
 * 는 형식만 보므로 사칭을 막지 못한다.
 *
 * <p><b>왜 공유 토큰 하나가 아니라 서명인가.</b> 토큰이면 요청 하나가 로그·프록시로 새는 순간
 * <b>모든 소유자를 사칭</b>할 수 있다. 서명은 특정 {@code owner_key} 와 만료 시각에 묶여 있어,
 * 새어 나간 헤더로는 <b>그 사람으로, 만료 전까지만</b> 행세할 수 있다. 문을 잠그는 효과는 같다 —
 * 비밀키 없이는 서명을 만들 수 없으므로 애초에 호출이 되지 않는다.
 *
 * <p>헤더 형태: {@code X-Owner-Auth: v1.<만료 epoch 초>.<base64url HMAC-SHA256>}
 *
 * <p>서명 대상은 {@code "v1." + ownerKey + "." + exp} 다. <b>버전과 만료를 서명 안에 넣는 이유</b>:
 * 밖에 두면 공격자가 만료를 늘려 무기한 재사용할 수 있다.
 *
 * <p><b>owner_key 가 없는 요청도 서명한다</b> (예: 공개 통계). 그때 서명 대상의 owner 자리는
 * 빈 문자열이다 — 서명이 없으면 통과시키는 예외를 두면 그 경로가 그대로 뒷문이 된다.
 */
public final class ServiceAuth {

	public static final String OWNER_HEADER = "X-Owner-Key";

	public static final String AUTH_HEADER = "X-Owner-Auth";

	private static final String VERSION = "v1";

	private static final String HMAC_ALGORITHM = "HmacSHA256";

	/**
	 * 만료가 이보다 더 먼 미래면 거절한다.
	 *
	 * <p>공격자는 서명을 만들 수 없으니 만료를 늘릴 수 없다. 이 상한은 <b>우리 쪽 실수</b>를
	 * 막는 것이다 — 프론트가 실수로 1년짜리를 발급하면 사실상 영구 토큰이 된다.
	 */
	private static final long MAX_LIFETIME_SECONDS = 600;

	/** 시계가 조금 어긋나도 정상 요청이 거절되지 않게 한다. */
	private static final long CLOCK_SKEW_SECONDS = 30;

	private ServiceAuth() {
	}

	public enum Result {

		OK,
		/** 헤더가 아예 없다. */
		MISSING,
		/** 형태가 깨졌거나 버전이 다르다. */
		MALFORMED,
		/** 만료됐거나, 만료가 지나치게 먼 미래다. */
		EXPIRED,
		/** 서명이 맞지 않는다 — <b>owner_key 를 바꿔치기한 경우도 여기로 온다.</b> */
		INVALID
	}

	/**
	 * @param ownerKey {@code X-Owner-Key} 헤더. 없으면 null
	 * @param authHeader {@code X-Owner-Auth} 헤더. 없으면 null
	 * @param secret 공유 비밀키
	 * @param now 현재 시각 — 테스트가 만료를 다룰 수 있도록 주입받는다
	 */
	public static Result verify(String ownerKey, String authHeader, String secret, Instant now) {
		if (authHeader == null || authHeader.isBlank()) {
			return Result.MISSING;
		}

		String[] parts = authHeader.split("\\.");
		if (parts.length != 3 || !VERSION.equals(parts[0])) {
			return Result.MALFORMED;
		}

		long exp;
		try {
			exp = Long.parseLong(parts[1]);
		}
		catch (NumberFormatException ex) {
			return Result.MALFORMED;
		}

		long nowSeconds = now.getEpochSecond();
		if (exp + CLOCK_SKEW_SECONDS < nowSeconds) {
			return Result.EXPIRED;
		}
		if (exp > nowSeconds + MAX_LIFETIME_SECONDS + CLOCK_SKEW_SECONDS) {
			return Result.EXPIRED;
		}

		byte[] expected = sign(payload(ownerKey, exp), secret);
		byte[] actual;
		try {
			actual = Base64.getUrlDecoder().decode(parts[2]);
		}
		catch (IllegalArgumentException ex) {
			return Result.MALFORMED;
		}

		// **일반 equals 를 쓰지 않는다.** 앞에서부터 비교하다 다르면 즉시 끝나는 구현은
		// 걸린 시간으로 몇 바이트가 맞았는지 새어 나간다.
		return MessageDigest.isEqual(expected, actual) ? Result.OK : Result.INVALID;
	}

	/** 프론트가 만드는 것과 같은 값. 테스트와 문서화를 위해 서버에도 둔다. */
	public static String sign(String ownerKey, Instant expiresAt, String secret) {
		long exp = expiresAt.getEpochSecond();
		String signature = Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(sign(payload(ownerKey, exp), secret));
		return VERSION + "." + exp + "." + signature;
	}

	/** owner 자리가 서명에 들어가야 헤더만 바꿔치기하는 것을 막는다. */
	private static String payload(String ownerKey, long exp) {
		return VERSION + "." + (ownerKey == null ? "" : ownerKey) + "." + exp;
	}

	private static byte[] sign(String payload, String secret) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
			return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception ex) {
			// 알고리즘이 없거나 키가 비었다 — 설정 문제이므로 조용히 통과시키면 안 된다.
			throw new IllegalStateException("서비스 인증 서명을 만들지 못했습니다", ex);
		}
	}
}
