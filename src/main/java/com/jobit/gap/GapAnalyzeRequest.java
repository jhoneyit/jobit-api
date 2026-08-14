package com.jobit.gap;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * {@code POST /api/gap-analyses} 본문 (docs/api.md "갭 분석").
 *
 * <p>검증 메시지는 사용자에게 그대로 보여줄 문구다 ({@code ApiExceptionHandler} 규약).
 */
public record GapAnalyzeRequest(

		@NotNull(message = "이력서를 선택해 주세요.") UUID resumeId,

		@NotNull(message = "공고를 선택해 주세요.") UUID jobPostingId) {
}
