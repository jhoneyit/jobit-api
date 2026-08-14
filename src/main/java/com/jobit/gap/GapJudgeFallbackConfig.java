package com.jobit.gap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 설정이 없을 때 {@link GapJudge} 자리를 채운다.
 *
 * <p>이게 없으면 갭 분석 서비스를 조립하지 못해 <b>앱이 아예 뜨지 않는다.</b> 설정 없이도
 * 캐시된 분석 결과 조회는 굴려 볼 수 있어야 한다.
 *
 * <p><b>호출되면 예외를 던진다.</b> MISSING 을 반환하면 "판정이 안 된 것"과 "정말 근거 없음"이
 * 구분되지 않고, 그 결과가 {@code (resume, jobPosting)} 캐시에 굳어 계속 재사용된다
 * ({@code JdParserFallbackConfig} 와 같은 판단).
 */
@Configuration
@Slf4j
public class GapJudgeFallbackConfig {

	@Bean
	@ConditionalOnMissingBean(GapJudge.class)
	public GapJudge unavailableGapJudge() {
		log.warn("GapJudge 구현이 없습니다. 갭 분석을 호출하면 실패합니다 (캐시된 결과 조회는 정상 동작합니다).");
		return request -> {
			throw new GapJudgeNotConfiguredException();
		};
	}

	public static class GapJudgeNotConfiguredException extends IllegalStateException {

		public GapJudgeNotConfiguredException() {
			super("GapJudge 구현이 등록되지 않았습니다. LLM 클라이언트를 붙여야 갭 분석이 동작합니다.");
		}
	}
}
