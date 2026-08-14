package com.jobit.gap;

/**
 * WEAK 판정을 받은 이력서 문장 하나를 요구사항 관점에서 고쳐 쓴다 (스펙 §4.4).
 *
 * <p>입력이 문장 하나인 것은 성능이 아니라 <b>규칙</b>이다 — "리라이트는 문장 단위. 이력서 전체를
 * LLM 에 보내지 않는다" (작업 원칙). 원문 전체가 필요 없으므로 복호화 경로도 열리지 않는다.
 *
 * <p>인터페이스로 두는 이유는 {@code GapJudge} 와 같다 — {@code ollama.base-url} 이 없으면
 * {@link RewriterFallbackConfig} 의 폴백이 자리를 지킨다.
 */
public interface Rewriter {

	Suggestion rewrite(Request request);

	/**
	 * @param rationale 갭 판정이 남긴 "왜 WEAK 인가". 무엇을 보강해야 하는지가 여기 들어 있다 —
	 *                  이것 없이 고치면 요구사항과 무관한 방향으로 다듬는다
	 */
	record Request(String requirementText, String rationale, String bulletText) {
	}

	record Suggestion(String suggested, String reason) {
	}
}
