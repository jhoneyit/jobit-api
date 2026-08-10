package com.jobit.llm;

/**
 * 기능별로 얼마나 생각하게 할지.
 *
 * <p><b>Anthropic 의 {@code OutputConfig.Effort} 를 대신한다.</b> 그쪽은 세 단계가 서버에서 추론
 * 예산으로 번역됐지만, Ollama 에는 그런 다이얼이 없다 — Qwen3 가 주는 것은
 * <b>thinking 을 켜고 끄는 스위치 하나</b>({@code think})뿐이다. 그래서 세 단계를 그대로 옮기지
 * 않고, <b>{@link #HIGH} 만 thinking 을 켠다</b>로 접었다.
 *
 * <p><b>세 단계를 남겨 둔 이유.</b> 값을 둘로 줄이면 {@code LOW} 와 {@code MEDIUM} 의 구분이
 * 코드에서 사라지는데, 그 구분은 "지금은 같은 취급이지만 성격이 다른 호출"이라는 판단을 담고
 * 있다 (구조화 추출 vs 판정). 나중에 판정 쪽만 thinking 을 켜고 싶어질 때 돌아올 자리다.
 *
 * <p><b>thinking 을 켜면 느려진다.</b> 로컬에서는 이게 곧 사용자 대기 시간이다 — 클라우드에서
 * effort 를 올리는 것은 돈 문제였지만 여기서는 시간 문제다. 그래서 기본값은 대부분 꺼져 있다
 * ({@link LlmModelConfig} 참고).
 */
public enum Effort {

	LOW,

	MEDIUM,

	HIGH;

	/**
	 * Ollama {@code /api/chat} 의 {@code think} 로 넘어갈 값.
	 *
	 * <p><b>이 값은 모델이 thinking 을 지원해야 의미가 있다.</b> 지원하지 않는 모델에 {@code think}
	 * 를 실으면 Ollama 가 400 을 돌려준다. Qwen3 계열은 지원한다 — 다른 모델로 바꾸려면
	 * {@code ollama show <model>} 의 capabilities 에 {@code thinking} 이 있는지 먼저 본다.
	 */
	public boolean think() {
		return this == HIGH;
	}
}
