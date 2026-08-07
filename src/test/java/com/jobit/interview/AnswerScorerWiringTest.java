package com.jobit.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobit.PostgresTestContainer;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * {@link AnswerScorer} 구현이 상황에 맞게 갈리는지 확인한다.
 *
 * <p>{@code JdParserWiringTest}와 같은 이유다 — 어긋나면 <b>조용히</b> 망가진다. 키를 넣었는데
 * 폴백이 남아 있으면 운영에서 채점이 전부 실패하고, 반대로 키가 없는데 실제 구현이 등록되면
 * 앱이 아예 뜨지 않는다.
 */
class AnswerScorerWiringTest {

	private static final AnswerScorer.Request REQUEST = new AnswerScorer.Request("질문",
			List.of("포인트 하나"), null, "답변");

	/**
	 * {@code anthropic.api-key=false}로 "키 없음"을 재현한다. 프로퍼티를 두지 않는 것으로는 안 되는데,
	 * OS 환경변수 {@code ANTHROPIC_API_KEY}가 relaxed binding으로 그대로 잡히기 때문이다
	 * ({@code JdParserWiringTest}에 자세히 적어 두었다).
	 */
	@Nested
	@SpringBootTest(properties = "anthropic.api-key=false")
	@Import(PostgresTestContainer.class)
	@DisplayName("API 키가 없으면")
	class WithoutApiKey {

		@Autowired
		private AnswerScorer scorer;

		@Test
		@DisplayName("폴백이 자리를 지켜 앱은 뜬다 — 세션 흐름과 기록 조회는 굴려볼 수 있다")
		void fallbackIsRegistered() {
			assertThat(scorer).isNotInstanceOf(AnthropicAnswerScorer.class);
		}

		@Test
		@DisplayName("폴백을 호출하면 예외를 던진다 — 0점이 기록에 남아 총점을 오염시키는 것보다 낫다")
		void fallbackThrowsWhenCalled() {
			assertThatThrownBy(() -> scorer.score(REQUEST)).isInstanceOf(
					AnswerScorerFallbackConfig.AnswerScorerNotConfiguredException.class);
		}
	}

	@Nested
	@SpringBootTest(properties = "anthropic.api-key=test-key-not-used")
	@Import(PostgresTestContainer.class)
	@DisplayName("API 키가 있으면")
	class WithApiKey {

		@Autowired
		private AnswerScorer scorer;

		@Test
		@DisplayName("실제 구현이 폴백을 밀어낸다")
		void anthropicScorerTakesOver() {
			assertThat(scorer).isInstanceOf(AnthropicAnswerScorer.class);
		}

		@Test
		@DisplayName("답변이 비어 있으면 LLM 을 부르지 않는다 — 키가 가짜라 불렀으면 터진다")
		void skipsLlmForEmptyTranscript() {
			// 제한 시간이 있는 이상 자주 일어나는 정상 경로다. 여기서 호출이 나가면
			// 채점할 내용도 없이 문항 수만큼 돈이 샌다.
			AnswerScorer.Score score = scorer
				.score(new AnswerScorer.Request("질문", List.of("포인트 하나", "포인트 둘"), null, "   "));

			assertThat(score.score()).isZero();
			assertThat(score.covered()).isEmpty();
			assertThat(score.missed()).containsExactly(0, 1);
		}

		@Test
		@DisplayName("뼈대가 없으면 채점하지 않는다 — 기준이 없는데 점수를 매기면 안 된다")
		void rejectsEmptyOutline() {
			assertThatThrownBy(
					() -> scorer.score(new AnswerScorer.Request("질문", List.of(), null, "답변")))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}
}
