package com.jobit.video;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 타임라인 샘플의 성질 — "영상 전체에 걸쳐 있고, 상한을 지키고, 시각이 실제 세그먼트에서 온다".
 *
 * <p>이게 어긋나면 섹션 좌표가 다시 t=0 으로 몰린다 (실사용에서 겪은 그 증상).
 */
class TranscriptTimelineTest {

	private static List<TranscriptSegment> talk(int durationSec, int stepSec) {
		List<TranscriptSegment> segments = new ArrayList<>();
		for (int t = 0; t < durationSec; t += stepSec) {
			segments.add(new TranscriptSegment(t, "구간 " + t + "초의 발화 내용입니다"));
		}
		return segments;
	}

	@Test
	@DisplayName("짧은 영상도 좌표가 여럿이다 — 7분 영상이 t=0 하나로 몰리면 안 된다")
	void shortVideoGetsMultipleCoordinates() {
		List<TranscriptSegment> samples = TranscriptTimeline.sample(talk(420, 5), 420);

		assertThat(samples.size()).isGreaterThanOrEqualTo(10);
		assertThat(samples.getFirst().startSec()).isZero();
		// 마지막 좌표가 영상 후반부에 있어야 전체를 덮은 것이다.
		assertThat(samples.getLast().startSec()).isGreaterThan(300);
	}

	@Test
	@DisplayName("긴 영상은 샘플 상한을 지킨다")
	void longVideoRespectsCap() {
		List<TranscriptSegment> samples = TranscriptTimeline.sample(talk(7200, 4), 7200);

		assertThat(samples).hasSizeLessThanOrEqualTo(TranscriptTimeline.MAX_SAMPLES);
		assertThat(samples.getLast().startSec()).isGreaterThan(5000);
	}

	@Test
	@DisplayName("조각은 글자 상한에서 잘리고, 시각은 실제 세그먼트의 것이다")
	void snippetsAreBoundedAndAnchored() {
		List<TranscriptSegment> samples = TranscriptTimeline.sample(talk(300, 5), 300);

		for (TranscriptSegment sample : samples) {
			assertThat(sample.text().length())
				.isLessThanOrEqualTo(TranscriptTimeline.SNIPPET_CHARS);
			assertThat(sample.startSec() % 5).isZero(); // 5초 간격 세그먼트에서 왔다
		}
	}

	@Test
	@DisplayName("길이를 모르는 영상(0)은 마지막 세그먼트 시각으로 간격을 잡는다")
	void unknownDurationFallsBackToLastSegment() {
		assertThat(TranscriptTimeline.sample(talk(600, 5), 0)).isNotEmpty();
		assertThat(TranscriptTimeline.sample(List.of(), 0)).isEmpty();
	}
}
