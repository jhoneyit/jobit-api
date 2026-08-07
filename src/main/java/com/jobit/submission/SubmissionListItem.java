package com.jobit.submission;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.jobit.gap.GapSummary;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 이력 목록 한 줄 (스펙 §4.6, docs/api.md).
 *
 * <p>이 record 가 곧 응답 형태다. 엔티티를 직렬화하지 않는 이유는 {@code JdParseResult}와 같다 —
 * {@code JobPosting.rawText}(공고 원문 전체)가 목록 N건마다 실려 나간다.
 *
 * @param submissionId 삭제 대상 식별자. <b>{@code jobPostingId}로는 한 줄을 지목할 수 없다</b> —
 *                     같은 공고를 여러 사람이 갖고 있어서, 소유자까지 함께 봐야 줄이 정해진다
 * @param parsed       이미 JSON 문자열이므로 다시 이스케이프하지 않고 그대로 내보낸다
 * @param gapSummary   아직 갭 분석을 돌리지 않았으면 {@code null}. 0/0/0으로 채우지 않는 이유는
 *                     "분석 전"과 "요구사항이 하나도 없음"을 화면에서 구분해야 하기 때문이다
 * @param updatedAt    제출 시각이 아니라 <b>마지막으로 넣은 시각</b>이다 (스펙 §3.6의 {@code touch})
 */
public record SubmissionListItem(UUID submissionId, UUID jobPostingId, String company,
		String title, @JsonRawValue String parsed, String memo, long requirementCount,
		long questionCount, OffsetDateTime updatedAt, GapSummary gapSummary) {

	public boolean analyzed() {
		return gapSummary != null;
	}
}
