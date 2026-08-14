package com.jobit.gap;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobit.llm.Effort;
import com.jobit.llm.JsonSchemas;
import com.jobit.llm.LlmFeature;
import com.jobit.llm.LlmModelConfig;
import com.jobit.llm.OllamaChatClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 리라이트 요청 조립을 고정한다 ({@code GapJudgeParamsTest} 와 같은 자리).
 */
class RewriteParamsTest {

	private static final Rewriter.Request REQUEST = new Rewriter.Request("RDBMS 튜닝 경험",
			"언급은 있으나 수치가 없다", "정산 배치를 운영했습니다");

	private static OllamaChatClient.Request build() {
		return OllamaRewriter.buildRequest(REQUEST, LlmModelConfig.of(LlmFeature.REWRITE));
	}

	@Test
	@DisplayName("REWRITE 설정을 그대로 쓴다 — 문장 품질이 제품 가치라 thinking 을 켠다")
	void usesRewriteConfig() {
		OllamaChatClient.Request request = build();
		LlmModelConfig.FeatureConfig config = LlmModelConfig.of(LlmFeature.REWRITE);

		assertThat(request.model()).isEqualTo(config.model());
		assertThat(request.effort()).isEqualTo(Effort.HIGH);
		assertThat(request.effort().think()).isTrue();
		assertThat(request.numPredict()).isEqualTo(config.maxTokens());
	}

	@Test
	@DisplayName("스키마는 RewriteResponse 에서 파생된다")
	void derivesSchemaFromResponseRecord() {
		assertThat(build().schema()).isEqualTo(JsonSchemas.of(RewriteResponse.class));
	}

	@Test
	@DisplayName("세 입력이 전부 실린다 — 판정 이유가 빠지면 무관한 방향으로 다듬는다")
	void carriesAllInputs() {
		OllamaChatClient.Request request = build();

		assertThat(request.system()).isEqualTo(RewritePrompts.SYSTEM);
		assertThat(request.user()).contains("RDBMS 튜닝 경험")
			.contains("언급은 있으나 수치가 없다")
			.contains("정산 배치를 운영했습니다");
	}
}
