package com.jobit.gap;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobit.llm.Effort;
import com.jobit.llm.JsonSchemas;
import com.jobit.llm.LlmFeature;
import com.jobit.llm.LlmModelConfig;
import com.jobit.llm.OllamaChatClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 갭 판정 요청 조립을 고정한다 ({@code AnswerScoreParamsTest} 와 같은 자리).
 *
 * <p>프로덕션과 같은 조립({@link OllamaGapJudge#buildRequest})을 그대로 부른다 — 테스트가 조립을
 * 복사해 두면 프로덕션만 고쳤을 때 옛 요청을 검사하며 통과한다.
 */
class GapJudgeParamsTest {

	private static final GapJudge.Request REQUEST = new GapJudge.Request("RDBMS 스키마 설계 경험",
			List.of(new GapJudge.Candidate(UUID.randomUUID(), "인덱스를 재설계했다"),
					new GapJudge.Candidate(UUID.randomUUID(), "배치를 운영했다")));

	private static OllamaChatClient.Request build() {
		return OllamaGapJudge.buildRequest(REQUEST,
				LlmModelConfig.of(LlmFeature.GAP_ANALYSIS));
	}

	@Test
	@DisplayName("GAP_ANALYSIS 설정을 그대로 쓴다 — 판정이라 thinking 을 켜지 않는다")
	void usesGapAnalysisConfig() {
		OllamaChatClient.Request request = build();
		LlmModelConfig.FeatureConfig config = LlmModelConfig.of(LlmFeature.GAP_ANALYSIS);

		assertThat(request.model()).isEqualTo(config.model());
		assertThat(request.effort()).isEqualTo(Effort.MEDIUM);
		assertThat(request.numPredict()).isEqualTo(config.maxTokens());
	}

	@Test
	@DisplayName("스키마는 GapJudgeResponse 에서 파생된다")
	void derivesSchemaFromResponseRecord() {
		assertThat(build().schema()).isEqualTo(JsonSchemas.of(GapJudgeResponse.class));
	}

	@Test
	@DisplayName("요구사항과 후보 문장이 유저 메시지에 실린다")
	void carriesRequirementAndCandidates() {
		OllamaChatClient.Request request = build();

		assertThat(request.system()).isEqualTo(GapJudgePrompts.SYSTEM);
		assertThat(request.user()).contains("RDBMS 스키마 설계 경험")
			.contains("0. 인덱스를 재설계했다")
			.contains("1. 배치를 운영했다");
	}
}
