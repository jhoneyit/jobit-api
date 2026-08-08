package com.jobit.llm;

import java.util.List;

/**
 * 문장을 벡터로 바꾸는 클라이언트 (스펙 §4.3 1단계).
 *
 * <p><b>왜 Anthropic 이 아닌가.</b> Anthropic 은 임베딩 API 를 제공하지 않는다 — Messages,
 * Batches, Files, Token Counting, Models 어디에도 임베딩 엔드포인트가 없다. 그래서 이 제품에서
 * 유일하게 <b>두 번째 LLM 제공자가 필요한 지점</b>이다. 인터페이스를 따로 둔 이유가 이것이다:
 * 제공자를 갈아탈 때 도메인 코드가 흔들리지 않아야 한다.
 *
 * <p><b>차원 수는 스키마가 정한다.</b> {@code resume_bullet.embedding} 이 {@code vector(1536)}
 * 이므로 구현체는 반드시 1536차원을 내야 한다. 모델을 바꿔 차원이 달라지면 Flyway 마이그레이션이
 * 먼저다 — {@link #dimensions()} 로 부팅 시점에 어긋남을 잡는다.
 */
public interface EmbeddingClient {

	/**
	 * 여러 문장을 <b>한 번의 호출로</b> 임베딩한다.
	 *
	 * <p>문장마다 호출하지 않는 이유는 비용이 아니라 왕복 시간이다. 이력서 하나가 문장 30개면
	 * 30번의 HTTP 왕복이 그대로 사용자 대기 시간이 된다.
	 *
	 * @param texts 빈 리스트를 넘기면 빈 리스트를 돌려준다 (호출하지 않는다)
	 * @return 입력과 <b>같은 순서·같은 개수</b>의 벡터
	 */
	List<float[]> embedAll(List<String> texts);

	/** 이 구현이 내는 벡터의 차원. {@code resume_bullet.embedding} 의 선언과 같아야 한다. */
	int dimensions();
}
