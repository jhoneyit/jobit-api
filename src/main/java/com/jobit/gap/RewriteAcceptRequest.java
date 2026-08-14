package com.jobit.gap;

import jakarta.validation.constraints.NotNull;

/**
 * {@code PATCH /api/rewrite-suggestions/{id}} 본문 (docs/api.md "리라이트").
 *
 * <p>{@code Boolean} 인 것은 필수 검증 때문이다 — 원시 {@code boolean} 이면 본문에서 빠졌을 때
 * 조용히 {@code false}(철회)가 되어, 채택하려던 요청이 반대로 동작한다.
 */
public record RewriteAcceptRequest(

		@NotNull(message = "accepted 값을 보내 주세요.") Boolean accepted) {
}
