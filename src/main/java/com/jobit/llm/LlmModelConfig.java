package com.jobit.llm;

import java.util.Map;

/**
 * 기능별 모델·effort·출력 상한.
 *
 * <p>스펙 §2는 "파싱은 저렴한 모델 / 리라이트는 상위 모델"로 티어를 나누라고 한다. 로컬 추론에서는
 * 그 축이 <b>돈에서 시간으로</b> 바뀐다 — 모델을 키우면 요금이 아니라 사용자 대기 시간이 늘고,
 * 18GB 한 대에서 두 모델을 동시에 물려 두면 서로 메모리를 뺏는다. 그래서 <b>모델은 하나로 두고
 * thinking 여부로만 조절한다</b>.
 *
 * <p><b>thinking 은 {@link Effort#HIGH} 에서만 켠다.</b> 지금 HIGH 인 것은 질문 생성과 리라이트
 * 둘뿐이다 — 결과 문장이 곧 제품 가치인 두 기능이다. 나머지는 구조화 추출·판정이라 사고 과정보다
 * 형식 준수가 중요하고, 켜 봐야 응답만 몇 배 느려진다.
 *
 * <h2>{@code maxTokens} 와 {@code num_ctx}</h2>
 *
 * <b>이 값들이 예전보다 작다.</b> Anthropic 시절의 {@code maxTokens} 는 거의 닿지 않는 천장이라
 * 넉넉히 잡아 두면 그만이었지만, Ollama 에서는 출력이
 * {@code jobit.llm.ollama.num-ctx}(기본 16384) 를 입력과 <b>나눠 쓴다.</b> 출력 상한을 그 값 가까이
 * 잡으면 긴 공고나 이력서가 들어왔을 때 입력 쪽이 조용히 잘린다
 * ({@link OllamaChatClient} 클래스 주석의 함정 1).
 *
 * <p>대략의 기준: {@code 입력 예상치 + maxTokens < num-ctx}. 지금 가장 큰 것이 8,000 이라
 * 입력에 8,000 토큰(한국어로 대략 5~6천 자)이 남는다. 더 긴 이력서를 다루려면 {@code num-ctx} 를
 * 올린다 — 다만 KV 캐시가 그만큼 메모리를 먹는다.
 */
public final class LlmModelConfig {

	/**
	 * 기본 모델.
	 *
	 * <p><b>{@code ollama pull} 로 미리 받아 둬야 한다.</b> 없으면 첫 호출이 404 로 실패한다 —
	 * Ollama 는 없는 모델을 자동으로 내려받지 않는다.
	 */
	public static final String DEFAULT_MODEL = "qwen3:14b";

	private static final Map<LlmFeature, FeatureConfig> CONFIGS = Map.of(
			// 구조화 추출이라 깊은 추론이 필요 없다. thinking 을 끄고 빠르게 받는다.
			LlmFeature.JD_PARSE, new FeatureConfig(DEFAULT_MODEL, Effort.LOW, 4_000L),
			// 제품의 첫인상을 결정하는 지점. 여기는 thinking 을 켠다.
			LlmFeature.QUESTION_GEN, new FeatureConfig(DEFAULT_MODEL, Effort.HIGH, 8_000L),
			// 이력서 → 문장 분해. JD 파싱과 성격이 같은 구조화 추출이다.
			// **출력 상한은 JD 파싱보다 크다** — 출력이 이력서 문장 전체라 입력만큼 길다.
			LlmFeature.RESUME_PARSE, new FeatureConfig(DEFAULT_MODEL, Effort.LOW, 8_000L),
			// 3단계 — 요구사항 1개 + 후보 문장 3개로 판정만. 입력이 짧다.
			LlmFeature.GAP_ANALYSIS, new FeatureConfig(DEFAULT_MODEL, Effort.MEDIUM, 2_000L),
			// 4단계 — 문장 하나를 고쳐 쓴다. 문장 품질이 곧 제품 가치라 thinking 을 켠다.
			LlmFeature.REWRITE, new FeatureConfig(DEFAULT_MODEL, Effort.HIGH, 2_000L),
			// 면접 답변 채점 — 답변 하나가 뼈대를 짚었는지 보는 판정이다. 성격이 갭 분석과 같다.
			// **호출 수가 많은 유일한 기능이라** (세션 1건 = 문항 수만큼) thinking 을 켜면
			// 세션 전체 시간이 문항 수만큼 곱해져 늘어난다. 여기만은 켜지 않는다.
			LlmFeature.ANSWER_SCORING, new FeatureConfig(DEFAULT_MODEL, Effort.MEDIUM, 2_000L),
			// 영상 자막 청크 요약 — 구조화 추출과 같은 결이고, 청크 수만큼 반복이라 채점과
			// 같은 이유로 thinking 을 켜면 안 된다.
			LlmFeature.VIDEO_CHUNK, new FeatureConfig(DEFAULT_MODEL, Effort.LOW, 800L),
			// 보고서 통합 — 결과 문장이 곧 제품 가치인 세 번째 기능. 영상당 한 번이라 켠다.
			LlmFeature.VIDEO_REPORT, new FeatureConfig(DEFAULT_MODEL, Effort.HIGH, 3_000L));

	private LlmModelConfig() {
	}

	/**
	 * <p><b>{@link LlmFeature#EMBEDDING} 은 여기 없다 — 빠뜨린 것이 아니다.</b> 임베딩에는 이 표의
	 * 두 축(effort·출력 상한)이 성립하지 않고, 모델은 {@code resume_bullet.embedding} 의 차원과
	 * 묶여 있어 이 표에서 고를 수 있는 값이 아니다. {@code OllamaEmbeddingClient} 가 자기 프로퍼티로
	 * 받는다 — 여기서 바꿀 수 있게 두면 스키마와 어긋난 모델을 고를 수 있게 된다.
	 */
	public static FeatureConfig of(LlmFeature feature) {
		FeatureConfig config = CONFIGS.get(feature);
		if (config == null) {
			throw new IllegalStateException("no model config for feature: " + feature);
		}
		return config;
	}

	public record FeatureConfig(String model, Effort effort, long maxTokens) {
	}
}
