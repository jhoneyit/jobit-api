package com.jobit.llm;

/**
 * LLM을 호출하는 기능 단위. {@code llm_call_log.feature}에 그대로 들어간다 (스펙 §3.5).
 *
 * <p>비용 대시보드가 "어느 기능이 돈을 먹는지" 보여주는 축이므로, 새 LLM 호출 경로를 만들면
 * 여기에 상수를 추가한다.
 */
public enum LlmFeature {

	JD_PARSE,

	QUESTION_GEN,

	/** 이력서 본문을 문장(bullet) 단위로 쪼갠다 (스펙 §3.3). */
	RESUME_PARSE,

	/**
	 * 문장 임베딩 (스펙 §4.3 1단계).
	 *
	 * <p><b>이것만 모델이 다르다.</b> 나머지 기능은 전부 {@link LlmModelConfig#DEFAULT_MODEL} 을
	 * 쓰지만 임베딩은 전용 모델이 필요해서 {@code llm_call_log.model} 에 다른 값이 들어오는
	 * 유일한 기능이다.
	 *
	 * <p><b>예전에는 제공자까지 달랐다</b> — Anthropic 에 임베딩 API 가 없어 OpenAI 를 썼다.
	 * Ollama 는 둘을 한 서버에서 주므로 그 예외는 사라졌고, 외부로 나가는 호출이 하나도 남지
	 * 않았다.
	 *
	 * <p>호출 수가 유독 많지만 문장 하나가 짧아 토큰 합계는 작다 — 장부에서 거의 보이지 않는 것이
	 * 정상이다.
	 */
	EMBEDDING,

	GAP_ANALYSIS,

	REWRITE,

	/**
	 * 면접 연습 답변 채점 (docs/interview-practice-design.md).
	 *
	 * <p><b>비용 대시보드에서 이것만 성격이 다르다.</b> 다른 기능은 요청 1건 = 호출 1회지만
	 * 이건 세션 1건 = 문항 수만큼이다. 합계를 볼 때 호출 수가 유독 많은 것이 정상이다.
	 */
	ANSWER_SCORING
}
