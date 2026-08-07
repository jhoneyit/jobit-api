package com.jobit.interview;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.jobit.llm.LlmFeature;
import com.jobit.llm.LlmModelConfig;
import com.jobit.llm.StructuredOutput;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AnthropicAnswerScorer}가 만드는 요청 파라미터를 고정한다.
 *
 * <p>{@code JdParseParamsTest}와 같은 이유다 — {@code outputConfig(Class)}가 effort를 조용히
 * 지우는 함정은 기능마다 따로 밟는다. <b>채점은 호출 수가 많은 유일한 기능이라</b>
 * (세션 1건 = 문항 수만큼) effort가 날아가 기본값(high)으로 돌면 비용이 다른 기능보다
 * 빠르게 샌다.
 */
class AnswerScoreParamsTest {

	private static final List<String> OUTLINE = List.of("격리 수준 4가지", "팬텀 리드", "실무 선택 기준");

	private StructuredMessageCreateParams<AnswerScoreResponse> buildParams() {
		LlmModelConfig.FeatureConfig config = LlmModelConfig.of(LlmFeature.ANSWER_SCORING);
		return StructuredOutput.withEffort(MessageCreateParams.builder()
			.model(config.model())
			.maxTokens(config.maxTokens())
			.thinking(ThinkingConfigAdaptive.builder().build())
			.outputConfig(AnswerScoreResponse.class)
			.system(AnswerScorePrompts.SYSTEM)
			.addUserMessage(AnswerScorePrompts.userMessage("트랜잭션 격리 수준을 설명해 주세요.", OUTLINE,
					"RDBMS 트랜잭션 이해", "격리 수준은 네 가지가 있고요...")),
				config.effort());
	}

	@Test
	@DisplayName("effort와 구조화 출력 스키마가 함께 요청에 실린다")
	void carriesBothEffortAndSchema() {
		OutputConfig outputConfig = buildParams().rawParams().outputConfig().orElseThrow();

		assertThat(outputConfig.effort()).contains(OutputConfig.Effort.MEDIUM);
		assertThat(outputConfig.format())
			.as("구조화 출력 스키마가 effort 설정에 덮이면 안 된다")
			.isPresent();
	}

	@Test
	@DisplayName("채점은 갭 분석과 같은 effort — 둘 다 판정 작업이다")
	void usesMediumEffortLikeGapAnalysis() {
		assertThat(LlmModelConfig.of(LlmFeature.ANSWER_SCORING).effort())
			.isEqualTo(LlmModelConfig.of(LlmFeature.GAP_ANALYSIS).effort());
	}

	@Test
	@DisplayName("시스템 프롬프트는 top-level system 으로 간다 — messages[0] 에 넣으면 400")
	void putsSystemPromptAtTopLevel() {
		MessageCreateParams raw = buildParams().rawParams();

		assertThat(raw.system()).isPresent();
		assertThat(raw.messages()).hasSize(1);
	}

	@Test
	@DisplayName("thinking을 끄지 않는다")
	void keepsThinkingOn() {
		assertThat(buildParams().rawParams().thinking().orElseThrow().adaptive()).isPresent();
	}

	@Test
	@DisplayName("응답 타입이 구조화 출력 대상으로 잡힌다")
	void bindsOutputType() {
		assertThat(buildParams().outputType()).isEqualTo(AnswerScoreResponse.class);
	}
}
