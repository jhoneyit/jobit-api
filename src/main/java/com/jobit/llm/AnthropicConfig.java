package com.jobit.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Anthropic 클라이언트.
 *
 * <p><b>{@code AnthropicOkHttpClient.fromEnv()}를 쓰지 않는다.</b> 그쪽은 OS 환경변수만 읽으므로
 * {@code application-local.properties}나 {@code spring.config.import}로 넣은 값을 찾지 못한다.
 * 여기서는 Spring Environment에서 받아 {@code builder().apiKey(...)}로 직접 넘긴다 — OS 환경변수
 * {@code ANTHROPIC_API_KEY}도 완화된 바인딩으로 {@code anthropic.api-key}에 그대로 잡힌다.
 *
 * <p>키가 없으면 <b>빈을 만들지 않는다.</b> 그러면 {@link com.jobit.jd.JdParserFallbackConfig}의
 * 폴백이 그대로 남아, 앱은 뜨되 파싱을 호출하는 순간 명확한 예외가 난다. 키 없이도 캐시·이력
 * 로직은 굴려볼 수 있다.
 */
@Configuration
@ConditionalOnProperty(name = "anthropic.api-key")
@Slf4j
public class AnthropicConfig {

	/**
	 * LLM 호출은 길게 돈다. SDK 기본 10분이지만 명시해 둔다 — 이 값이 컨트롤러의 응답 지연
	 * 상한이기도 하다.
	 */
	private static final Duration TIMEOUT = Duration.ofMinutes(3);

	@Bean
	public AnthropicClient anthropicClient(
			@org.springframework.beans.factory.annotation.Value("${anthropic.api-key}") String apiKey) {
		log.info("Anthropic client configured (timeout={})", TIMEOUT);
		return AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(TIMEOUT).build();
	}
}
