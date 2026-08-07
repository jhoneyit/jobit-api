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

	REWRITE,

	/**
	 * 면접 연습 답변 채점 (docs/interview-practice-design.md).
	 *
	 * <p><b>비용 대시보드에서 이것만 성격이 다르다.</b> 다른 기능은 요청 1건 = 호출 1회지만
	 * 이건 세션 1건 = 문항 수만큼이다. 합계를 볼 때 호출 수가 유독 많은 것이 정상이다.
	 */
	ANSWER_SCORING
}
