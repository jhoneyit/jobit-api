package com.jobit.jd;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 파싱 구현이 들어오기 전까지 {@link JdParser} 자리를 채운다.
 *
 * <p>이게 없으면 {@link JdParsingService}를 조립하지 못해 앱이 아예 뜨지 않는다. 캐시·이력 로직을
 * LLM 없이 먼저 굴려보려면 부팅은 되어야 한다.
 *
 * <p><b>호출되면 예외를 던진다.</b> 빈 결과를 반환하면 "요구사항이 하나도 없는 공고"가 캐시에
 * 저장되고, {@code content_hash} 때문에 그 쓰레기가 계속 재사용된다.
 *
 * <p>{@link ConditionalOnMissingBean}이라 실제 구현을 등록하면 자동으로 물러난다.
 * 클래스 이름을 빈 메서드 이름과 다르게 둔 것은 설정 클래스 자신의 빈 이름과 충돌하기 때문이다.
 */
@Configuration
@Slf4j
public class JdParserFallbackConfig {

	/**
	 * <b>경고를 빈 메서드 안에서 낸다.</b> 설정 클래스의 {@code @PostConstruct}에 두면 폴백이
	 * 실제로 쓰이는지와 무관하게 <b>항상</b> 찍힌다 — 키를 제대로 넣어도 "구현이 없습니다"가
	 * 뜨는 것이다. 늘 뜨는 경고는 곧 읽히지 않게 되고, 그러면 진짜로 폴백이 물린 날에도
	 * 아무도 알아채지 못한다.
	 */
	@Bean
	@ConditionalOnMissingBean(JdParser.class)
	public JdParser unavailableJdParser() {
		log.warn("JdParser 구현이 없습니다. JD 파싱을 호출하면 실패합니다 "
				+ "(캐시 적중 시에는 파서를 타지 않으므로 정상 동작합니다).");
		return rawText -> {
			throw new JdParserNotConfiguredException();
		};
	}

	public static class JdParserNotConfiguredException extends IllegalStateException {

		public JdParserNotConfiguredException() {
			super("JdParser 구현이 등록되지 않았습니다. LLM 클라이언트를 붙여야 JD 파싱이 동작합니다.");
		}
	}
}
