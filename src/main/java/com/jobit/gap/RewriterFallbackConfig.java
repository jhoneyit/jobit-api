package com.jobit.gap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 설정이 없을 때 {@link Rewriter} 자리를 채운다.
 *
 * <p><b>호출되면 예외를 던진다.</b> 빈 수정안을 반환하면 "만들지 못한 것"과 "고칠 게 없는 것"이
 * 구분되지 않고, 그 값이 {@code gap_item} 당 하나뿐인 제안 자리에 굳는다
 * ({@code GapJudgeFallbackConfig} 와 같은 판단).
 */
@Configuration
@Slf4j
public class RewriterFallbackConfig {

	@Bean
	@ConditionalOnMissingBean(Rewriter.class)
	public Rewriter unavailableRewriter() {
		log.warn("Rewriter 구현이 없습니다. 리라이트를 호출하면 실패합니다 (제안 조회·채택은 정상 동작합니다).");
		return request -> {
			throw new RewriterNotConfiguredException();
		};
	}

	public static class RewriterNotConfiguredException extends IllegalStateException {

		public RewriterNotConfiguredException() {
			super("Rewriter 구현이 등록되지 않았습니다. LLM 클라이언트를 붙여야 리라이트가 동작합니다.");
		}
	}
}
