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

	GAP_ANALYSIS,

	REWRITE
}
