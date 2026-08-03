package com.jobit.gap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GapSummaryTest {

	private static final UUID POSTING_A = UUID.randomUUID();

	private static final UUID POSTING_B = UUID.randomUUID();

	@Test
	@DisplayName("공고별로 상태 카운트를 접는다")
	void foldsCountsPerPosting() {
		List<Object[]> rows = List.of(
				new Object[] { POSTING_A, GapItem.Status.MET, 8L },
				new Object[] { POSTING_A, GapItem.Status.WEAK, 3L },
				new Object[] { POSTING_A, GapItem.Status.MISSING, 2L },
				new Object[] { POSTING_B, GapItem.Status.MET, 1L });

		Map<UUID, GapSummary> result = GapSummary.fold(rows);

		assertThat(result).hasSize(2);
		assertThat(result.get(POSTING_A))
			.isEqualTo(new GapSummary(POSTING_A, 8L, 3L, 2L));
		assertThat(result.get(POSTING_A).total()).isEqualTo(13L);
		assertThat(result.get(POSTING_B))
			.isEqualTo(new GapSummary(POSTING_B, 1L, 0L, 0L));
	}

	@Test
	@DisplayName("등장하지 않은 상태는 0으로 채운다")
	void missingStatusesBecomeZero() {
		List<Object[]> rows = List.<Object[]>of(
				new Object[] { POSTING_A, GapItem.Status.MISSING, 5L });

		assertThat(GapSummary.fold(rows).get(POSTING_A))
			.isEqualTo(new GapSummary(POSTING_A, 0L, 0L, 5L));
	}

	@Test
	@DisplayName("분석하지 않은 공고는 맵에 없다 — 0/0/0과 구분되어야 한다")
	void unanalyzedPostingIsAbsent() {
		Map<UUID, GapSummary> result = GapSummary.fold(List.of());

		assertThat(result).doesNotContainKey(POSTING_A);
		assertThat(result.get(POSTING_A)).isNull();
	}
}
