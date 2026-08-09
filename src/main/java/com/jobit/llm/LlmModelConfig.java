package com.jobit.llm;

import com.anthropic.models.messages.OutputConfig;
import java.util.Map;

/**
 * 기능별 모델·effort 설정.
 *
 * <p>스펙 §2는 "파싱은 저렴한 모델 / 리라이트는 상위 모델"로 티어를 나누라고 한다. 다만 텍스트
 * 품질이 곧 제품 가치인 유형(§6)이라, <b>기본값은 전부 최상위 모델로 두고 비용은 effort로 먼저</b>
 * 조절한다 — 파싱은 low, 질문 생성은 high. {@code jobit-front}의 {@code llm/config.ts}와 같은 값이다.
 *
 * <p>비용이 유의미해지면 {@code /api/cost}로 어느 기능이 먹는지 확인한 뒤 여기 {@code model}만
 * 내리면 된다.
 *
 * <p><b>thinking을 끄지 않는다.</b> Opus 5는 thinking이 기본으로 켜져 있고, 끄면 (1) 도구 호출이
 * 일반 텍스트로 새거나 (2) {@code <thinking>} 태그가 응답에 섞이는 실패 모드가 있다. 비용은 effort로
 * 낮추는 편이 안전하다. 또한 {@code maxTokens}는 thinking과 응답을 <b>합쳐서</b> 제한하므로
 * 넉넉히 잡는다.
 */
public final class LlmModelConfig {

	/** 기본 모델. 티어를 내릴 때 여기부터 본다. */
	public static final String DEFAULT_MODEL = "claude-opus-5";

	private static final Map<LlmFeature, FeatureConfig> CONFIGS = Map.of(
			// 구조화 추출이라 깊은 추론이 필요 없다. effort로 비용을 낮춘다.
			LlmFeature.JD_PARSE, new FeatureConfig(DEFAULT_MODEL, OutputConfig.Effort.LOW, 8_000L),
			// 제품의 첫인상을 결정하는 지점. 품질에 투자한다.
			LlmFeature.QUESTION_GEN,
			new FeatureConfig(DEFAULT_MODEL, OutputConfig.Effort.HIGH, 16_000L),
			// 이력서 → 문장 분해. JD 파싱과 성격이 같은 구조화 추출이라 effort 도 같다.
			// **maxTokens 는 JD 파싱보다 크다** — 출력이 이력서 문장 전체라 입력만큼 길다.
			LlmFeature.RESUME_PARSE,
			new FeatureConfig(DEFAULT_MODEL, OutputConfig.Effort.LOW, 16_000L),
			// 3단계 — 요구사항 1개 + 후보 문장 3개로 판정만. 입력이 짧다.
			LlmFeature.GAP_ANALYSIS,
			new FeatureConfig(DEFAULT_MODEL, OutputConfig.Effort.MEDIUM, 4_000L),
			// 4단계 — 문장 하나를 고쳐 쓴다. 문장 품질이 곧 제품 가치.
			LlmFeature.REWRITE, new FeatureConfig(DEFAULT_MODEL, OutputConfig.Effort.HIGH, 4_000L),
			// 면접 답변 채점 — 답변 하나가 뼈대를 짚었는지 보는 판정이다. 성격이 갭 분석과
			// 같아 effort 도 같이 간다. **호출 수가 많은 유일한 기능이라** (세션 1건 =
			// 문항 수만큼) effort 를 올리면 비용이 다른 기능보다 빠르게 는다.
			LlmFeature.ANSWER_SCORING,
			new FeatureConfig(DEFAULT_MODEL, OutputConfig.Effort.MEDIUM, 4_000L));

	private LlmModelConfig() {
	}

	/**
	 * <p><b>{@link LlmFeature#EMBEDDING} 은 여기 없다 — 빠뜨린 것이 아니다.</b> 임베딩은
	 * 제공자가 Anthropic 이 아니라 이 표의 두 축(모델 이름·effort)이 성립하지 않고,
	 * 모델은 {@code resume_bullet.embedding} 의 차원과 묶여 있어 설정으로 바꿀 수 있는
	 * 값도 아니다. 그래서 {@code OpenAiEmbeddingClient} 안에 상수로 박혀 있다 —
	 * 여기서 바꿀 수 있게 두면 스키마와 어긋난 모델을 고를 수 있게 된다.
	 */
	public static FeatureConfig of(LlmFeature feature) {
		FeatureConfig config = CONFIGS.get(feature);
		if (config == null) {
			throw new IllegalStateException("no model config for feature: " + feature);
		}
		return config;
	}

	public record FeatureConfig(String model, OutputConfig.Effort effort, long maxTokens) {
	}
}
