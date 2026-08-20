package com.jobit.video;

import java.util.ArrayList;
import java.util.List;

/**
 * 보고서 단계에 주는 시간 좌표계 — 자막을 일정 간격으로 샘플한 {@code [t=초] 발화 조각} 목록.
 *
 * <p><b>왜 필요한가.</b> 처음에는 청크 시작 시각만 보고서 단계에 넘겼는데, 짧은 영상은 청크가
 * 하나라 모델이 고를 수 있는 좌표가 {@code t=0} 하나뿐이었다 — 7분 영상의 섹션이 전부 0:00 이
 * 된 실사용 보고가 그 결과다. 청크 요약 몇 문장만으로는 섹션을 나눌 재료도 부족했다
 * (섹션 1개짜리 보고서). 타임라인은 좌표와 재료를 함께 준다: 영상 전체에 걸친 실제 발화
 * 조각이 어느 시각에 무엇을 말했는지를 촘촘히 보여준다.
 */
final class TranscriptTimeline {

	/** 샘플 수 상한. 40개 × ~80자 ≈ 3천 자 — 보고서 입력에 얹어도 num_ctx 에 여유가 있다. */
	static final int MAX_SAMPLES = 40;

	/** 최소 간격(초). 아주 짧은 영상을 초 단위로 도배하지 않는다. */
	static final int MIN_INTERVAL_SEC = 15;

	/** 조각 하나의 글자 상한. 좌표를 잡는 데 문장 전체가 필요하지 않다. */
	static final int SNIPPET_CHARS = 80;

	private TranscriptTimeline() {
	}

	static List<TranscriptSegment> sample(List<TranscriptSegment> segments, int durationSec) {
		if (segments.isEmpty()) {
			return List.of();
		}
		int span = durationSec > 0 ? durationSec : segments.getLast().startSec();
		int interval = Math.max(MIN_INTERVAL_SEC, span / MAX_SAMPLES);

		List<TranscriptSegment> samples = new ArrayList<>();
		int nextTick = 0;
		StringBuilder pending = new StringBuilder();
		int pendingStart = -1;

		// 틱마다 "그 시점부터의 발화"를 조각 상한까지 이어 붙인다 — 자동 자막은 단어 단위라
		// 세그먼트 하나로는 문장이 안 된다.
		for (TranscriptSegment segment : segments) {
			if (pendingStart < 0 && segment.startSec() >= nextTick) {
				pendingStart = segment.startSec();
			}
			if (pendingStart >= 0) {
				if (!pending.isEmpty()) {
					pending.append(' ');
				}
				pending.append(segment.text());
				if (pending.length() >= SNIPPET_CHARS) {
					samples.add(new TranscriptSegment(pendingStart,
							pending.substring(0, SNIPPET_CHARS)));
					pending.setLength(0);
					pendingStart = -1;
					nextTick = samples.getLast().startSec() + interval;
					if (samples.size() >= MAX_SAMPLES) {
						break;
					}
				}
			}
		}
		if (pendingStart >= 0 && !pending.isEmpty() && samples.size() < MAX_SAMPLES) {
			samples.add(new TranscriptSegment(pendingStart, pending.toString()));
		}
		return samples;
	}
}
