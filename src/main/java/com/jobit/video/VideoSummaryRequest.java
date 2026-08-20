package com.jobit.video;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /api/video-summaries} 본문. 검증 메시지는 사용자 문구다. */
public record VideoSummaryRequest(

		@NotBlank(message = "유튜브 영상 주소를 넣어 주세요.")
		@Size(max = 500, message = "주소가 너무 깁니다.") String url) {
}
