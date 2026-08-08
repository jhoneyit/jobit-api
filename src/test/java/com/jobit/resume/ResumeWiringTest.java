package com.jobit.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobit.PostgresTestContainer;
import com.jobit.llm.EmbeddingClient;
import com.jobit.llm.EmbeddingClientFallbackConfig;
import com.jobit.llm.OpenAiEmbeddingClient;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 이력서 경로의 빈이 상황에 맞게 갈리는지.
 *
 * <p>{@code JdParserWiringTest} 와 같은 이유로 존재한다 — 어긋나면 <b>조용히</b> 망가진다.
 * 특히 여기는 <b>제공자가 둘</b>이라 실패 조합이 더 많다: Anthropic 키만 있고 OpenAI 키가 없으면
 * 분해는 되는데 임베딩에서 죽고, 그 반대면 아무것도 못 한다.
 *
 * <p>{@code anthropic.api-key=false} 로 "키 없음"을 재현하는 이유는 {@code JdParserWiringTest}
 * 주석 참고 — 빈 문자열로는 안 되고, OS 환경변수가 완화 바인딩으로 새어 들어온다.
 */
class ResumeWiringTest {

	@Nested
	@SpringBootTest(properties = { "anthropic.api-key=false", "openai.api-key=false" })
	@Import(PostgresTestContainer.class)
	@DisplayName("키가 하나도 없으면")
	class WithoutKeys {

		@Autowired
		private ResumeParser resumeParser;

		@Autowired
		private EmbeddingClient embeddingClient;

		@Test
		@DisplayName("폴백이 자리를 지켜 앱은 뜬다 — 무관한 기능까지 죽으면 안 된다")
		void fallbacksAreRegistered() {
			assertThat(resumeParser).isNotInstanceOf(AnthropicResumeParser.class);
			assertThat(embeddingClient).isNotInstanceOf(OpenAiEmbeddingClient.class);
		}

		@Test
		@DisplayName("분해 폴백을 호출하면 예외를 던진다")
		void parserFallbackThrows() {
			assertThatThrownBy(() -> resumeParser.parse("아무 이력서")).isInstanceOf(
					ResumeParserFallbackConfig.ResumeParserNotConfiguredException.class);
		}

		@Test
		@DisplayName("임베딩 폴백을 호출하면 예외를 던진다 — 0 벡터를 반환하지 않는다")
		void embeddingFallbackThrows() {
			assertThatThrownBy(() -> embeddingClient.embedAll(List.of("문장"))).isInstanceOf(
					EmbeddingClientFallbackConfig.EmbeddingNotConfiguredException.class);
		}
	}

	@Nested
	@SpringBootTest(properties = { "anthropic.api-key=test-key-not-used",
			"openai.api-key=test-key-not-used" })
	@Import(PostgresTestContainer.class)
	@DisplayName("키가 둘 다 있으면")
	class WithKeys {

		@Autowired
		private ResumeParser resumeParser;

		@Autowired
		private EmbeddingClient embeddingClient;

		@Test
		@DisplayName("실제 구현이 폴백을 밀어낸다")
		void realImplementationsTakeOver() {
			assertThat(resumeParser).isInstanceOf(AnthropicResumeParser.class);
			assertThat(embeddingClient).isInstanceOf(OpenAiEmbeddingClient.class);
		}

		@Test
		@DisplayName("임베딩 차원이 스키마의 vector(1536) 과 같다")
		void dimensionsMatchSchema() {
			assertThat(embeddingClient.dimensions()).isEqualTo(1536);
		}
	}
}
