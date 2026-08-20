package com.jobit.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TranscriptChunkerTest {

	private static List<TranscriptSegment> segments(int count, int charsEach) {
		List<TranscriptSegment> list = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			list.add(new TranscriptSegment(i * 10, "가".repeat(charsEach)));
		}
		return list;
	}

	@Test
	@DisplayName("청크가 자기 시작 시각을 기억한다 — 보고서 타임스탬프의 근원이다")
	void preservesStartTimestamps() {
		// 조각 하나가 목표 크기의 절반 → 두 조각씩 묶인다.
		List<TranscriptChunker.Chunk> chunks = TranscriptChunker
			.chunk(segments(6, TranscriptChunker.TARGET_CHARS / 2));

		assertThat(chunks).hasSize(3);
		assertThat(chunks.get(0).startSec()).isZero();
		assertThat(chunks.get(1).startSec()).isEqualTo(20);
		assertThat(chunks.get(2).startSec()).isEqualTo(40);
	}

	@Test
	@DisplayName("남는 꼬리도 청크가 된다 — 마지막 구간을 버리면 결말 없는 요약이 된다")
	void keepsTrailingRemainder() {
		List<TranscriptChunker.Chunk> chunks = TranscriptChunker
			.chunk(segments(5, TranscriptChunker.TARGET_CHARS / 2));

		assertThat(chunks).hasSize(3);
		assertThat(chunks.get(2).text().length())
			.isLessThan(TranscriptChunker.TARGET_CHARS / 2 + 10);
	}

	@Test
	@DisplayName("상한을 넘는 영상은 예외다 — 앞부분만 조용히 요약하면 거짓말이 된다")
	void rejectsTooLongTranscripts() {
		assertThatThrownBy(() -> TranscriptChunker
			.chunk(segments((TranscriptChunker.MAX_CHUNKS + 1) * 2,
					TranscriptChunker.TARGET_CHARS / 2)))
			.isInstanceOf(TranscriptChunker.TooLongException.class);
	}

	@Test
	@DisplayName("빈 자막은 빈 목록이다")
	void emptySegmentsYieldNoChunks() {
		assertThat(TranscriptChunker.chunk(List.of())).isEmpty();
	}
}
