package com.jobit.interview;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 키가 없을 때 {@link AnswerScorer} 자리를 채운다.
 *
 * <p>이게 없으면 채점을 주입받는 서비스를 조립하지 못해 <b>앱이 아예 뜨지 않는다.</b>
 * 키 없이도 세션 흐름(시작·답변 저장·기록 조회)은 굴려 볼 수 있어야 한다.
 *
 * <p><b>호출되면 예외를 던진다.</b> 0점을 반환하면 "채점이 안 된 것"과 "정말 0점"이 구분되지
 * 않고, 그 값이 기록에 남아 총점까지 오염된다 ({@code JdParserFallbackConfig}와 같은 판단 —
 * 그쪽도 빈 결과가 캐시에 굳는 것을 막으려고 던진다).
 *
 * <p>{@link ConditionalOnMissingBean}이라 실제 구현이 등록되면 자동으로 물러난다.
 */
@Configuration
@Slf4j
public class AnswerScorerFallbackConfig {

	@Bean
	@ConditionalOnMissingBean(AnswerScorer.class)
	public AnswerScorer unavailableAnswerScorer() {
		log.warn("AnswerScorer 구현이 없습니다. 면접 답변 채점을 호출하면 실패합니다 "
				+ "(세션 시작과 기록 조회는 정상 동작합니다).");
		return request -> {
			throw new AnswerScorerNotConfiguredException();
		};
	}

	public static class AnswerScorerNotConfiguredException extends IllegalStateException {

		public AnswerScorerNotConfiguredException() {
			super("AnswerScorer 구현이 등록되지 않았습니다. LLM 클라이언트를 붙여야 채점이 동작합니다.");
		}
	}
}
