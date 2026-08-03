package com.jobit.gap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 이력 목록에 한 줄로 보여줄 갭 요약 (스펙 §4.6): {@code 충족 8 / 약함 3 / 없음 2}.
 *
 * <p>목록 N건마다 {@code gap_item}을 전부 읽지 않도록 집계 쿼리로 뽑는다.
 */
public record GapSummary(UUID jobPostingId, long met, long weak, long missing) {

	public long total() {
		return met + weak + missing;
	}

	/**
	 * {@link GapItemRepository#countByStatus}의 {@code (jobPostingId, status, count)} 행들을
	 * 공고별 요약으로 접는다.
	 *
	 * <p>아직 분석하지 않은 공고는 결과 맵에 없다 — 호출부에서 "분석 전"으로 표시한다.
	 * 0/0/0으로 채우면 "요구사항이 하나도 없는 공고"와 구분되지 않는다.
	 */
	public static Map<UUID, GapSummary> fold(List<Object[]> rows) {
		Map<UUID, long[]> acc = new LinkedHashMap<>();
		for (Object[] row : rows) {
			UUID jobPostingId = (UUID) row[0];
			GapItem.Status status = (GapItem.Status) row[1];
			long count = (Long) row[2];
			long[] counts = acc.computeIfAbsent(jobPostingId, key -> new long[3]);
			counts[status.ordinal()] += count;
		}

		Map<UUID, GapSummary> result = new LinkedHashMap<>();
		acc.forEach((jobPostingId, counts) -> result.put(jobPostingId, new GapSummary(
				jobPostingId,
				counts[GapItem.Status.MET.ordinal()],
				counts[GapItem.Status.WEAK.ordinal()],
				counts[GapItem.Status.MISSING.ordinal()])));
		return result;
	}
}
