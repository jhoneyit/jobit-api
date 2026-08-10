package com.jobit.interview;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobit.llm.JsonSchemas;
import com.jobit.llm.LlmFeature;
import com.jobit.llm.LlmModelConfig;
import com.jobit.llm.OllamaChatClient;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OllamaAnswerScorer}가 만드는 요청을 고정한다.
 *
 * <p>{@code JdParseParamsTest}와 같은 이유다. <b>다만 여기서만 걸리는 조건이 하나 있다</b> —
 * 채점은 호출 수가 많은 유일한 기능이라(세션 1건 = 문항 수만큼) thinking 이 켜지면 세션 전체
 * 시간이 문항 수만큼 곱해져 늘어난다. 다른 기능에서는 느려짐이 한 번이지만 여기서는 다섯 번이다.
 *
 * <p><b>캐시 관련 검사가 통째로 사라졌다.</b> 예전에는 시스템 프롬프트의 {@code cache_control} 과
 * TTL 을 못박았는데, Ollama 에는 프롬프트 캐싱 API 가 없다 — 프리픽스 재사용은 일어나지만
 * 요청에 표시할 것도 응답에서 확인할 것도 없어서 고정할 대상 자체가 없다.
 */
class AnswerScoreParamsTest {

	private static final List<String> OUTLINE = List.of("격리 수준 4가지", "팬텀 리드", "실무 선택 기준");

	private static final String TRANSCRIPT = "격리 수준은 네 가지가 있고요...";

	/**
	 * <b>프로덕션 조립을 그대로 부른다.</b> 여기서 요청을 다시 만들면, 프로덕션만 바뀌었을 때
	 * 테스트는 옛 형태를 검사하며 통과한다 — 형태를 고정하겠다는 테스트가 형태 변경을 놓친다.
	 */
	private OllamaChatClient.Request buildRequest() {
		return OllamaAnswerScorer.buildRequest(
				new AnswerScorer.Request("트랜잭션 격리 수준을 설명해 주세요.", OUTLINE, "RDBMS 트랜잭션 이해",
						TRANSCRIPT),
				LlmModelConfig.of(LlmFeature.ANSWER_SCORING));
	}

	@Test
	@DisplayName("구조화 출력 스키마가 요청에 실린다")
	void carriesSchema() {
		assertThat(buildRequest().schema()).isEqualTo(JsonSchemas.of(AnswerScoreResponse.class));
	}

	@Test
	@DisplayName("채점은 갭 분석과 같은 effort — 둘 다 판정 작업이다")
	void usesSameEffortAsGapAnalysis() {
		assertThat(LlmModelConfig.of(LlmFeature.ANSWER_SCORING).effort())
			.isEqualTo(LlmModelConfig.of(LlmFeature.GAP_ANALYSIS).effort());
	}

	@Test
	@DisplayName("채점은 thinking 을 켜지 않는다 — 세션 하나가 문항 수만큼 이걸 반복한다")
	void doesNotThink() {
		assertThat(buildRequest().effort().think())
			.as("한 번의 느려짐이 아니라 문항 수만큼의 느려짐이 된다")
			.isFalse();
	}

	@Test
	@DisplayName("시스템 프롬프트가 사용자 메시지와 분리되어 실린다")
	void keepsSystemPromptSeparate() {
		OllamaChatClient.Request request = buildRequest();

		assertThat(request.system()).isEqualTo(AnswerScorePrompts.SYSTEM);
		assertThat(request.user()).contains(TRANSCRIPT);
		assertThat(request.user()).doesNotContain(AnswerScorePrompts.SYSTEM);
	}

	@Test
	@DisplayName("출력 상한이 설정에서 그대로 넘어간다")
	void carriesOutputLimit() {
		assertThat(buildRequest().numPredict())
			.isEqualTo(LlmModelConfig.of(LlmFeature.ANSWER_SCORING).maxTokens());
	}
}
