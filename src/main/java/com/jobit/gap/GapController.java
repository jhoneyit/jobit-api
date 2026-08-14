package com.jobit.gap;

import com.jobit.common.OwnerKey;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 갭 분석 엔드포인트 (스펙 §4.3·§4.5, docs/api.md "갭 분석").
 *
 * <p><b>{@code X-Owner-Key} 가 전부 필수다.</b> 이력서가 걸린 기능이라 소유자 없이 할 수 있는
 * 일이 없다 ({@code ResumeController} 와 같은 이유).
 *
 * <p>컨트롤러는 변환과 검증만 한다. 캐시·한도·판정·트랜잭션 경계는 {@link GapAnalysisService} 가
 * 갖는다.
 */
@RestController
@RequestMapping(path = "/api/gap-analyses", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class GapController {

	private final GapAnalysisService gapAnalysisService;

	/**
	 * {@code POST /api/gap-analyses} — 분석을 실행하거나, 이미 있으면 그 결과를 돌려준다.
	 *
	 * <p><b>느리다.</b> 판정이 요구사항 수만큼 반복되어 몇 분이 걸릴 수 있다 — 프론트는 로딩
	 * 상태를 반드시 보여 줘야 한다. 캐시 적중이면 즉시 돌아온다 ({@code cached: true}).
	 */
	@PostMapping
	public GapView.Analyzed analyze(@RequestHeader("X-Owner-Key") String ownerKey,
			@Valid @RequestBody GapAnalyzeRequest request) {

		return GapView.of(gapAnalysisService.analyze(OwnerKey.requireValid(ownerKey),
				request.resumeId(), request.jobPostingId()));
	}

	/**
	 * {@code GET /api/gap-analyses} — 캐시된 결과 조회. 없으면 404 — <b>분석을 시작하지 않는다.</b>
	 *
	 * <p>GET 이 분석까지 해 버리면 재방문 화면을 그리려던 프론트가 의도치 않게 몇 분짜리 LLM
	 * 경로를 태우고 한도까지 소비한다. 시작은 언제나 명시적인 POST 다.
	 */
	@GetMapping
	public GapView.Analyzed get(@RequestHeader("X-Owner-Key") String ownerKey,
			@RequestParam UUID resumeId, @RequestParam UUID jobPostingId) {

		return GapView
			.of(gapAnalysisService.getExisting(OwnerKey.requireValid(ownerKey), resumeId,
					jobPostingId));
	}
}
