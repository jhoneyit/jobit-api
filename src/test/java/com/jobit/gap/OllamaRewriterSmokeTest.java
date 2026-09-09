package com.jobit.gap;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobit.llm.LlmCallRecorder;
import com.jobit.llm.LlmFeature;
import com.jobit.llm.OllamaChatClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 실제 Ollama 를 호출해 리라이트 경로 전체를 확인한다.
 *
 * <p><b>여기서만 볼 수 있는 것: 모델이 서버 재검증을 통과하는 문장을 내는가.</b>
 * {@link RewriteNormalizer} 가 지어낸 숫자를 거부하므로, 이 테스트가 통과한다는 것은 모델이
 * "수치는 자리 표시로 남긴다"를 실제로 지킨다는 뜻이다 — 재시도 2회 안에 못 지키면 예외가 난다.
 * thinking 을 켜는 기능이라({@code Effort.HIGH}) 다른 스모크보다 오래 걸린다.
 *
 * <pre>{@code
 * ollama pull qwen3:14b
 * JOBIT_LLM_SMOKE=1 ./gradlew test --tests '*OllamaRewriterSmokeTest*' -i
 * }</pre>
 */
@EnabledIfEnvironmentVariable(named = "JOBIT_LLM_SMOKE", matches = "(?i)1|true|on",
		disabledReason = "로컬 추론이 몇 분 걸린다. JOBIT_LLM_SMOKE=1 로 켠다.")
class OllamaRewriterSmokeTest {

	/** OS 환경변수로 다른 호스트를 볼 수 있게 해 둔다 — 기본은 로컬이다. */
	private static final String BASE_URL = System.getenv()
		.getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");

	@Test
	@DisplayName("수치 없는 WEAK 문장이 자리 표시를 얻는다 — 숫자를 지어내는 대신")
	void rewritesWithPlaceholders() {
		Rewriter rewriter = new OllamaRewriter(new OllamaChatClient(BASE_URL, 16_384, "1h"),
				new RecordingSpy());

		String original = "정산 배치를 운영했습니다.";
		Rewriter.Suggestion suggestion = rewriter.rewrite(new Rewriter.Request(
				"대용량 트래픽 처리 및 성능 개선 경험", "관련 언급은 있으나 규모와 성과 수치가 없다", original));

		System.out.printf("%n[리라이트] %s%n  → %s%n  이유: %s%n", original, suggestion.suggested(),
				suggestion.reason());

		// 정규화가 이미 보장하는 것들이지만 계약을 여기서 한 번 더 못박는다.
		assertThat(suggestion.suggested()).isNotBlank().isNotEqualTo(original);
		assertThat(suggestion.reason()).isNotBlank();
		// 판정 이유가 "수치가 없다"인데 원문에는 숫자가 없다 — 보강하려면 자리 표시밖에 없다.
		// 이게 깨지면 모델이 자리 표시 규칙을 무시하고 밋밋하게만 고친 것이다.
		assertThat(suggestion.suggested())
			.as("수치를 요구하는 판정에는 자리 표시([...])가 있어야 한다 — 숫자를 지어냈다면 정규화가 먼저 막았다")
			.contains("[").contains("]");
	}

	/** DB 대신 콘솔로 흘린다 — 추론 시간을 눈으로 확인하기 위한 것이다. */
	private static final class RecordingSpy extends LlmCallRecorder {

		private RecordingSpy() {
			super(null);
		}

		@Override
		public void record(LlmFeature feature, String model, long input, long output,
				boolean cacheHit, long latencyMs) {
			System.out.printf("[llm] %s model=%s in=%d out=%d %dms%n", feature, model, input,
					output, latencyMs);
		}
	}
}
