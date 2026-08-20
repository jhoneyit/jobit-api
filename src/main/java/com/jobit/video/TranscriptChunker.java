package com.jobit.video;

import java.util.ArrayList;
import java.util.List;

/**
 * 자막 조각 → LLM 청크 (영상 요약 1단계 입력).
 *
 * <p><b>왜 쪼개는가.</b> 1시간 영상 자막은 수만 자라 {@code num_ctx}(16384) 에 통째로 안 들어간다.
 * 청크 요약 → 통합의 map-reduce 가 유일한 길이고, 여기가 map 의 경계를 긋는다.
 *
 * <p><b>청크가 시작 시각을 기억한다.</b> 보고서 섹션의 타임스탬프(유튜브 {@code ?t=} 딥링크)가
 * 여기서 나온다 — 쪼갤 때 잃으면 되돌릴 방법이 없다.
 */
final class TranscriptChunker {

	/**
	 * 청크 목표 크기(자). 한국어 ≈ 1.5자/토큰으로 8,000자 ≈ 5,300토큰 — 프롬프트와 출력 상한을
	 * 더해도 {@code num_ctx} 안에 넉넉히 든다. 크게 잡을수록 호출이 줄지만 청크 요약의 해상도가
	 * 떨어진다.
	 */
	static final int TARGET_CHARS = 8_000;

	/**
	 * 청크 수 상한. 넘으면 뒤를 버리고 요약이 아니라 예외다 — 4시간을 넘는 영상은 팟캐스트
	 * 전체 녹화 같은 것이라, 조용히 앞부분만 요약하면 "요약했다"는 거짓말이 된다.
	 */
	static final int MAX_CHUNKS = 30;

	private TranscriptChunker() {
	}

	record Chunk(int startSec, String text) {
	}

	static List<Chunk> chunk(List<TranscriptSegment> segments) {
		List<Chunk> chunks = split(segments, TARGET_CHARS);
		if (chunks.size() > MAX_CHUNKS) {
			throw new TooLongException(chunks.size());
		}
		return chunks;
	}

	/** 크기만 다른 분할 — QnA 세립 청크(~1,000자)가 같은 로직을 쓴다. 상한 검사는 호출부 몫이다. */
	static List<Chunk> split(List<TranscriptSegment> segments, int targetChars) {
		List<Chunk> chunks = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		int currentStart = -1;

		for (TranscriptSegment segment : segments) {
			if (currentStart < 0) {
				currentStart = segment.startSec();
			}
			if (!current.isEmpty()) {
				current.append(' ');
			}
			current.append(segment.text());

			if (current.length() >= targetChars) {
				chunks.add(new Chunk(currentStart, current.toString()));
				current.setLength(0);
				currentStart = -1;
			}
		}
		if (!current.isEmpty()) {
			chunks.add(new Chunk(currentStart, current.toString()));
		}
		return chunks;
	}

	/** 요약 불가능한 길이. 사용자가 할 수 있는 일이 없으므로 문구가 사실을 말한다. */
	static class TooLongException extends RuntimeException {

		TooLongException(int chunks) {
			super("영상이 너무 깁니다 (자막 청크 " + chunks + "개 > " + MAX_CHUNKS + ")");
		}
	}
}
