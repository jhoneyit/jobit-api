package com.jobit.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 호출자 인증 서명 검증.
 *
 * <p>여기서 지키는 것은 <b>서명이 무엇에 묶여 있는가</b>다 — owner_key 와 만료 시각 둘 다에
 * 묶여 있어야, 새어 나간 헤더로 다른 사람 행세를 하거나 무기한 재사용하는 것을 막는다.
 */
class ServiceAuthTest {

	private static final String SECRET = "test-secret-that-is-long-enough-32";

	private static final String OWNER = "user:abc123";

	private static final Instant NOW = Instant.parse("2026-08-07T10:00:00Z");

	private String signFor(String ownerKey, Instant expiresAt) {
		return ServiceAuth.sign(ownerKey, expiresAt, SECRET);
	}

	@Test
	@DisplayName("우리가 만든 서명은 통과한다")
	void acceptsOwnSignature() {
		String auth = signFor(OWNER, NOW.plusSeconds(300));

		assertThat(ServiceAuth.verify(OWNER, auth, SECRET, NOW)).isEqualTo(ServiceAuth.Result.OK);
	}

	@Test
	@DisplayName("owner_key 를 바꿔치기하면 거절한다 — 이게 이 기능의 존재 이유다")
	void rejectsSwappedOwnerKey() {
		// 남의 요청을 가로챈 뒤 owner_key 만 자기 것으로(혹은 남의 것으로) 바꾼 상황.
		String auth = signFor(OWNER, NOW.plusSeconds(300));

		assertThat(ServiceAuth.verify("user:someone-else", auth, SECRET, NOW))
			.isEqualTo(ServiceAuth.Result.INVALID);
	}

	@Test
	@DisplayName("만료된 서명은 거절한다 — 새어 나간 헤더가 영원히 살면 안 된다")
	void rejectsExpired() {
		String auth = signFor(OWNER, NOW.minusSeconds(60));

		assertThat(ServiceAuth.verify(OWNER, auth, SECRET, NOW))
			.isEqualTo(ServiceAuth.Result.EXPIRED);
	}

	@Test
	@DisplayName("시계가 조금 어긋난 정도는 통과시킨다")
	void toleratesSmallClockSkew() {
		String auth = signFor(OWNER, NOW.minusSeconds(10));

		assertThat(ServiceAuth.verify(OWNER, auth, SECRET, NOW)).isEqualTo(ServiceAuth.Result.OK);
	}

	@Test
	@DisplayName("만료가 지나치게 먼 미래면 거절한다 — 우리 쪽 실수로 영구 토큰이 되는 것을 막는다")
	void rejectsFarFutureExpiry() {
		String auth = signFor(OWNER, NOW.plusSeconds(60 * 60 * 24 * 365));

		assertThat(ServiceAuth.verify(OWNER, auth, SECRET, NOW))
			.isEqualTo(ServiceAuth.Result.EXPIRED);
	}

	@Test
	@DisplayName("만료를 손으로 늘리면 서명이 깨진다 — 만료가 서명 안에 들어 있다")
	void cannotExtendExpiryWithoutSecret() {
		String auth = signFor(OWNER, NOW.minusSeconds(60));
		String[] parts = auth.split("\\.");
		String extended = parts[0] + "." + (NOW.getEpochSecond() + 300) + "." + parts[2];

		assertThat(ServiceAuth.verify(OWNER, extended, SECRET, NOW))
			.isEqualTo(ServiceAuth.Result.INVALID);
	}

	@Test
	@DisplayName("다른 비밀키로 만든 서명은 거절한다")
	void rejectsWrongSecret() {
		String auth = ServiceAuth.sign(OWNER, NOW.plusSeconds(300),
				"another-secret-that-is-long-enough");

		assertThat(ServiceAuth.verify(OWNER, auth, SECRET, NOW))
			.isEqualTo(ServiceAuth.Result.INVALID);
	}

	@Test
	@DisplayName("헤더가 없으면 MISSING — 서명 없는 호출은 통과시키지 않는다")
	void rejectsMissingHeader() {
		assertThat(ServiceAuth.verify(OWNER, null, SECRET, NOW))
			.isEqualTo(ServiceAuth.Result.MISSING);
		assertThat(ServiceAuth.verify(OWNER, "   ", SECRET, NOW))
			.isEqualTo(ServiceAuth.Result.MISSING);
	}

	@Test
	@DisplayName("형태가 깨진 헤더는 MALFORMED — 파싱하다 터지지 않는다")
	void rejectsMalformedHeader() {
		assertThat(ServiceAuth.verify(OWNER, "가짜", SECRET, NOW))
			.isEqualTo(ServiceAuth.Result.MALFORMED);
		assertThat(ServiceAuth.verify(OWNER, "v2.123.sig", SECRET, NOW))
			.isEqualTo(ServiceAuth.Result.MALFORMED);
		assertThat(ServiceAuth.verify(OWNER, "v1.만료아님.sig", SECRET, NOW))
			.isEqualTo(ServiceAuth.Result.MALFORMED);
		assertThat(ServiceAuth.verify(OWNER, "v1." + (NOW.getEpochSecond() + 60) + ".!!!not-base64!!!",
				SECRET, NOW)).isEqualTo(ServiceAuth.Result.MALFORMED);
	}

	@Test
	@DisplayName("owner_key 없는 요청도 서명한다 — 예외를 두면 그 경로가 뒷문이 된다")
	void signsRequestsWithoutOwnerKey() {
		// 공개 통계(/api/stats/stacks)가 이 경우다.
		String auth = signFor(null, NOW.plusSeconds(300));

		assertThat(ServiceAuth.verify(null, auth, SECRET, NOW)).isEqualTo(ServiceAuth.Result.OK);
		// owner 없이 서명한 것을 owner 있는 요청에 재사용할 수 없다.
		assertThat(ServiceAuth.verify(OWNER, auth, SECRET, NOW))
			.isEqualTo(ServiceAuth.Result.INVALID);
	}
}
