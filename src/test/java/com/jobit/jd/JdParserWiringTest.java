package com.jobit.jd;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobit.PostgresTestContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * {@link JdParser} 구현이 상황에 맞게 갈리는지 확인한다.
 *
 * <p>이게 어긋나면 <b>조용히</b> 망가진다. 키를 넣었는데 폴백이 남아 있으면 운영에서 파싱이 전부
 * 실패하고, 반대로 키가 없는데 실제 구현이 등록되면 앱이 아예 뜨지 않는다. 둘 다 부팅 로그만
 * 봐서는 알아채기 어렵다.
 */
class JdParserWiringTest {

	/**
	 * {@code anthropic.api-key=false}로 "키 없음"을 재현한다. 프로퍼티를 아예 두지 않는 것으로는
	 * 안 되는데, OS 환경변수 {@code ANTHROPIC_API_KEY}가 Spring의 relaxed binding으로 이
	 * 프로퍼티에 그대로 매핑되기 때문이다 — 스모크 테스트를 위해 키를 export 해 둔 기계에서는
	 * {@link AnthropicJdParser}가 등록되어 이 시나리오가 성립하지 않는다.
	 *
	 * <p>빈 문자열은 통하지 않는다. {@code @ConditionalOnProperty}는 값이 있기만 하면 매칭하고
	 * <b>{@code "false"}일 때만</b> 물러나므로, 환경과 무관하게 확실한 값이 {@code false}다.
	 */
	@Nested
	@SpringBootTest(properties = "anthropic.api-key=false")
	@Import(PostgresTestContainer.class)
	@DisplayName("API 키가 없으면")
	class WithoutApiKey {

		@Autowired
		private JdParser jdParser;

		@Test
		@DisplayName("폴백이 자리를 지켜 앱은 뜬다 — 캐시·이력 로직은 굴려볼 수 있다")
		void fallbackIsRegistered() {
			assertThat(jdParser).isNotInstanceOf(AnthropicJdParser.class);
		}

		@Test
		@DisplayName("폴백을 호출하면 예외를 던진다 — 빈 결과가 캐시에 굳는 것보다 낫다")
		void fallbackThrowsWhenCalled() {
			org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> jdParser.parse("아무 공고"))
				.isInstanceOf(JdParserFallbackConfig.JdParserNotConfiguredException.class);
		}
	}

	@Nested
	@SpringBootTest(properties = "anthropic.api-key=test-key-not-used")
	@Import(PostgresTestContainer.class)
	@DisplayName("API 키가 있으면")
	class WithApiKey {

		@Autowired
		private JdParser jdParser;

		@Test
		@DisplayName("실제 구현이 폴백을 밀어낸다")
		void anthropicParserTakesOver() {
			assertThat(jdParser).isInstanceOf(AnthropicJdParser.class);
		}
	}
}
