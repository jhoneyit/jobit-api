package com.jobit.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobit.PostgresTestContainer;
import com.jobit.llm.EmbeddingClient;
import com.jobit.llm.EmbeddingClientFallbackConfig;
import com.jobit.llm.OllamaEmbeddingClient;
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
 *
 * <p><b>제공자가 하나로 줄어 실패 조합도 줄었다.</b> 예전에는 분해(Anthropic)와 임베딩(OpenAI)이
 * 서로 다른 키를 봐서 "분해는 되는데 임베딩에서 죽는" 절반의 상태가 가능했다. 이제 둘 다
 * {@code ollama.base-url} 하나를 보므로 켜지고 꺼지는 것이 함께다 — 그래도 <b>둘을 같이 확인한다</b>:
 * 조건을 한쪽에만 붙이는 실수는 여전히 가능하고, 그때 생기는 것이 딱 그 절반의 상태다.
 *
 * <p>{@code ollama.base-url=false} 로 "설정 없음"을 재현하는 이유는 {@code JdParserWiringTest}
 * 주석 참고 — 빈 문자열로는 안 되고, OS 환경변수가 완화 바인딩으로 새어 들어온다.
 */
class ResumeWiringTest {

	@Nested
	@SpringBootTest(properties = "ollama.base-url=false")
	@Import(PostgresTestContainer.class)
	@DisplayName("Ollama 설정이 없으면")
	class WithoutOllama {

		@Autowired
		private ResumeParser resumeParser;

		@Autowired
		private EmbeddingClient embeddingClient;

		@Test
		@DisplayName("폴백이 자리를 지켜 앱은 뜬다 — 무관한 기능까지 죽으면 안 된다")
		void fallbacksAreRegistered() {
			assertThat(resumeParser).isNotInstanceOf(OllamaResumeParser.class);
			assertThat(embeddingClient).isNotInstanceOf(OllamaEmbeddingClient.class);
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
	@SpringBootTest(properties = "ollama.base-url=http://localhost:11434")
	@Import(PostgresTestContainer.class)
	@DisplayName("Ollama 설정이 있으면")
	class WithOllama {

		@Autowired
		private ResumeParser resumeParser;

		@Autowired
		private EmbeddingClient embeddingClient;

		@Test
		@DisplayName("실제 구현이 폴백을 밀어낸다")
		void realImplementationsTakeOver() {
			assertThat(resumeParser).isInstanceOf(OllamaResumeParser.class);
			assertThat(embeddingClient).isInstanceOf(OllamaEmbeddingClient.class);
		}

		/**
		 * <b>이 숫자가 어긋나면 저장이 통째로 실패한다.</b> {@code resume_bullet.embedding} 이
		 * {@code vector(1024)} 라 (Flyway V11) 임베딩 모델이 다른 차원을 내면 매 업로드가
		 * {@code ::vector} 캐스트에서 죽는다. 모델을 바꿀 때 마이그레이션을 잊는 것이 그 경로다.
		 */
		@Test
		@DisplayName("임베딩 차원이 스키마의 vector(1024) 와 같다")
		void dimensionsMatchSchema() {
			assertThat(embeddingClient.dimensions()).isEqualTo(1024);
		}
	}
}
