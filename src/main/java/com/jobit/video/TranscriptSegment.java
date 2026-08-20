package com.jobit.video;

/**
 * 자막·전사의 한 조각. 출처(유튜브 json3 / Whisper JSON)와 무관하게 이 형태로 통일한다.
 *
 * @param startSec 영상 내 시작 시각(초). 보고서 섹션의 딥링크({@code ?t=})가 여기서 나온다
 */
public record TranscriptSegment(int startSec, String text) {
}
