package com.jobit.gap;

import com.jobit.jd.Requirement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 갭 분석 응답 형태 (docs/api.md "갭 분석").
 *
 * <p><b>공고 메타(회사·직함)를 싣지 않는다.</b> 프론트는 이 화면에 오기 전에 공고를 이미 알고
 * 있고(파싱 응답 또는 제출 이력), 여기서 실으려면 지연 로딩된 {@code JobPosting} 을 트랜잭션
 * 밖에서 건드리게 된다.
 */
public final class GapView {

	private GapView() {
	}

	public static Analyzed of(GapAnalysisService.Result result) {
		List<Item> items = new ArrayList<>(result.items().size());
		long met = 0;
		long weak = 0;
		long missing = 0;

		for (GapItem item : result.items()) {
			switch (item.getStatus()) {
				case MET -> met++;
				case WEAK -> weak++;
				case MISSING -> missing++;
			}
			// evidence 는 findForDisplay 가 join fetch 로 실어 온다 — 여기서 지연 로딩이 일어나면
			// 트랜잭션 밖이라 터진다. 조회 쿼리가 계약의 일부인 이유다.
			Evidence evidence = item.getEvidenceBullet() == null ? null
					: new Evidence(item.getEvidenceBullet().getId(),
							item.getEvidenceBullet().getText());
			items.add(new Item(item.getId(), item.getRequirement().getId(),
					item.getRequirement().getText(), item.getRequirement().getKind(),
					item.getStatus(), evidence, item.getRationale()));
		}

		GapAnalysis analysis = result.analysis();
		return new Analyzed(analysis.getId(), result.cached(), analysis.getCreatedAt(),
				new Summary(met, weak, missing), items);
	}

	/**
	 * @param cached true 면 LLM 을 부르지 않고 기존 결과를 돌려준 것이다
	 */
	public record Analyzed(UUID gapAnalysisId, boolean cached, OffsetDateTime createdAt,
			Summary summary, List<Item> items) {
	}

	/** 제출 이력 목록의 {@code gapSummary} 와 같은 형태다 — 프론트가 같은 컴포넌트를 쓴다. */
	public record Summary(long met, long weak, long missing) {
	}

	/**
	 * @param gapItemId 리라이트 진입점이다 — {@code POST /api/gap-items/{gapItemId}/rewrite}.
	 *                  이 값이 없으면 프론트가 WEAK 행에서 수정안을 요청할 방법이 없다
	 *                  (실제로 빠뜨린 채 나갔다가 종단 확인에서 걸렸다, 2026-08-14)
	 * @param evidence {@code MISSING} 이면 null — "근거 없음"을 그대로 노출한다 (스펙 §4.5)
	 */
	public record Item(UUID gapItemId, UUID requirementId, String requirementText,
			Requirement.Kind kind, GapItem.Status status, Evidence evidence, String rationale) {
	}

	public record Evidence(UUID bulletId, String text) {
	}
}
