package com.jobit.gap;

import java.util.UUID;

/**
 * 리라이트 응답 형태 (docs/api.md "리라이트").
 *
 * <p>{@code original} 을 함께 내린다 — 화면이 원문과 수정안을 <b>나란히</b> 보여주는 것이
 * 스펙 §4.5 의 요구라, 프론트가 문장을 따로 조회하러 가지 않게 한다.
 *
 * @param cached true 면 LLM 을 부르지 않고 기존 제안을 돌려준 것이다
 */
public record RewriteView(UUID suggestionId, UUID gapItemId, UUID bulletId, String original,
		String suggested, String reason, boolean accepted, boolean cached) {

	public static RewriteView of(RewriteService.Result result) {
		RewriteSuggestion suggestion = result.suggestion();
		// 지연 프록시라도 getId() 는 초기화 없이 식별자를 돌려준다 — 그 이상은 건드리지 않는다.
		return new RewriteView(suggestion.getId(), suggestion.getGapItem().getId(),
				suggestion.getBullet().getId(), suggestion.getOriginal(),
				suggestion.getSuggested(), suggestion.getReason(), suggestion.isAccepted(),
				result.cached());
	}
}
