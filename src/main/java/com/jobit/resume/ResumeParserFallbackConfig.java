package com.jobit.resume;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 키가 없을 때 {@link ResumeParser} 자리를 채운다.
 *
 * <p>{@code JdParserFallbackConfig} 와 같은 구조이자 같은 이유다 — 이게 없으면
 * {@code ResumeService} 를 조립하지 못해 앱이 아예 뜨지 않는다.
 *
 * <p><b>호출되면 예외를 던진다.</b> 빈 문장 목록을 반환하면 "문장이 하나도 없는 이력서"가 저장되고,
 * 사용자는 업로드가 성공한 줄 알지만 갭 분석에서 근거를 하나도 찾지 못한다.
 */
@Configuration
@Slf4j
public class ResumeParserFallbackConfig {

	/** 경고를 빈 메서드 안에서 내는 이유는 {@code JdParserFallbackConfig} 주석 참고. */
	@Bean
	@ConditionalOnMissingBean(ResumeParser.class)
	public ResumeParser unavailableResumeParser() {
		log.warn("ResumeParser 구현이 없습니다. 이력서 업로드를 호출하면 실패합니다.");
		return rawText -> {
			throw new ResumeParserNotConfiguredException();
		};
	}

	public static class ResumeParserNotConfiguredException extends IllegalStateException {

		public ResumeParserNotConfiguredException() {
			super("ResumeParser 구현이 등록되지 않았습니다. LLM 클라이언트를 붙여야 이력서 분석이 동작합니다.");
		}
	}
}
