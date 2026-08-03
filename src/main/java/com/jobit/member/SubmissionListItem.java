package com.jobit.member;

import com.jobit.gap.GapSummary;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 이력 목록 한 줄 (스펙 §4.6).
 *
 * @param gapSummary 아직 갭 분석을 돌리지 않았으면 {@code null}. 0/0/0으로 채우지 않는 이유는
 *                   "분석 전"과 "요구사항이 하나도 없음"을 화면에서 구분해야 하기 때문이다.
 */
public record SubmissionListItem(UUID submissionId, UUID jobPostingId, String company,
		String title, String memo, OffsetDateTime updatedAt, GapSummary gapSummary) {

	public boolean analyzed() {
		return gapSummary != null;
	}
}
