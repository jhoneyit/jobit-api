package com.jobit.gap;

import com.jobit.common.OwnerKey;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 리라이트 엔드포인트 (스펙 §4.4·§4.5, docs/api.md "리라이트").
 *
 * <p>경로가 둘로 갈리는 이유: 수정안 생성은 <b>갭 항목</b>의 일이고(WEAK 항목에서 시작한다),
 * 채택은 <b>제안</b>의 일이다(이미 만들어진 제안에 표시를 남긴다). 자원이 다르면 경로도 다르다.
 */
@RestController
@RequiredArgsConstructor
public class RewriteController {

	private final RewriteService rewriteService;

	/**
	 * {@code POST /api/gap-items/{gapItemId}/rewrite} — 수정안을 만들거나, 이미 있으면 돌려준다.
	 *
	 * <p><b>느리다.</b> 유일하게 thinking 을 켜는 두 기능 중 하나라(문장 품질이 곧 제품 가치)
	 * 수십 초 걸린다 — 프론트는 로딩 상태를 반드시 보여 줘야 한다.
	 */
	@PostMapping(path = "/api/gap-items/{gapItemId}/rewrite",
			produces = MediaType.APPLICATION_JSON_VALUE)
	public RewriteView rewrite(@RequestHeader("X-Owner-Key") String ownerKey,
			@PathVariable UUID gapItemId) {

		return RewriteView.of(rewriteService.rewrite(OwnerKey.requireValid(ownerKey), gapItemId));
	}

	/**
	 * {@code PATCH /api/rewrite-suggestions/{suggestionId}} — 채택 여부를 기록한다.
	 *
	 * <p>서버는 표시만 남긴다 — 문장을 실제로 바꾸는 것은 사용자가 자기 이력서에서 할 일이다
	 * (이력서에 수정 개념이 없다: 고치면 새로 올린다).
	 */
	@PatchMapping(path = "/api/rewrite-suggestions/{suggestionId}",
			produces = MediaType.APPLICATION_JSON_VALUE)
	public RewriteView setAccepted(@RequestHeader("X-Owner-Key") String ownerKey,
			@PathVariable UUID suggestionId, @Valid @RequestBody RewriteAcceptRequest request) {

		return RewriteView.of(rewriteService.setAccepted(OwnerKey.requireValid(ownerKey),
				suggestionId, request.accepted()));
	}
}
