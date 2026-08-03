package com.jobit.jd;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/jd/parse} 요청 본문 (docs/api.md).
 *
 * <p>길이 상한은 프롬프트 주입 방어가 아니라 <b>비용 방어</b>다. 20만 자짜리 문서를 붙여넣으면
 * 입력 토큰만으로 호출 한 건이 비싸진다. 공고 본문은 이 범위를 넘지 않는다.
 */
public record JdParseRequest(

		@NotBlank(message = "공고 본문을 입력해 주세요.")
		@Size(min = 100, max = 50_000,
				message = "공고 본문은 100자 이상 50,000자 이하여야 합니다.") String text,

		@Size(max = 2_000, message = "원문 주소가 너무 깁니다.") String sourceUrl) {
}
