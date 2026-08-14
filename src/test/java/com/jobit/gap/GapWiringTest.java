package com.jobit.gap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobit.PostgresTestContainer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * {@link GapJudge} 구현이 상황에 맞게 갈리는지 확인한다.
 *
 * <p>{@code JdParserWiringTest}·{@code AnswerScorerWiringTest} 와 같은 이유다 — 어긋나면
 * <b>조용히</b> 망가진다.
 */
class GapWiringTest {

	private static final GapJudge.Request REQUEST = new GapJudge.Request("요구사항",
			List.of(new GapJudge.Candidate(UUID.randomUUID(), "문장")));

	/**
	 * {@code ollama.base-url=false} 로 "설정 없음"을 재현한다 — 이유는 {@code JdParserWiringTest}.
	 */
	@Nested
	@SpringBootTest(properties = "ollama.base-url=false")
	@Import(PostgresTestContainer.class)
	@DisplayName("Ollama 설정이 없으면")
	class WithoutLlm {

		@Autowired
		private GapJudge judge;

		@Test
		@DisplayName("폴백이 자리를 지켜 앱은 뜬다 — 캐시된 분석 결과 조회는 굴려볼 수 있다")
		void fallbackIsRegistered() {
			assertThat(judge).isNotInstanceOf(OllamaGapJudge.class);
		}

		@Test
		@DisplayName("폴백을 호출하면 예외를 던진다 — MISSING 이 캐시에 굳는 것보다 낫다")
		void fallbackThrowsWhenCalled() {
			assertThatThrownBy(() -> judge.judge(REQUEST))
				.isInstanceOf(GapJudgeFallbackConfig.GapJudgeNotConfiguredException.class);
		}
	}

	@Nested
	@SpringBootTest(properties = "ollama.base-url=http://localhost:11434")
	@Import(PostgresTestContainer.class)
	@DisplayName("Ollama 설정이 있으면")
	class WithLlm {

		@Autowired
		private GapJudge judge;

		@Test
		@DisplayName("실제 구현이 폴백을 밀어낸다")
		void ollamaJudgeTakesOver() {
			assertThat(judge).isInstanceOf(OllamaGapJudge.class);
		}

		@Test
		@DisplayName("후보가 없으면 판정하지 않는다 — 후보 없는 판정은 호출부가 끊었어야 한다")
		void rejectsEmptyCandidates() {
			assertThatThrownBy(() -> judge.judge(new GapJudge.Request("요구사항", List.of())))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}
}
