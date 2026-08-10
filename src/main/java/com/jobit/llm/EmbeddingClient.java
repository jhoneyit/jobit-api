package com.jobit.llm;

import java.util.List;

/**
 * 문장을 벡터로 바꾸는 클라이언트 (스펙 §4.3 1단계).
 *
 * <p><b>인터페이스를 따로 둔 값을 이미 한 번 받았다.</b> 이 자리의 구현은 OpenAI
 * {@code text-embedding-3-small} 이었다가 Ollama 로 갈아탔는데, 그때 바뀐 것은 구현체 하나와
 * 벡터 차원뿐이고 {@code ResumeService} 는 손대지 않았다.
 *
 * <p><b>차원 수는 스키마가 정한다.</b> {@code resume_bullet.embedding} 이 {@code vector(1024)}
 * 이므로 구현체는 반드시 1024차원을 내야 한다. 모델을 바꿔 차원이 달라지면 Flyway 마이그레이션이
 * 먼저고, <b>기존 벡터는 변환할 방법이 없어 재업로드가 따라온다</b> — 차원이 다른 임베딩 공간
 * 사이에는 대응이 없다.
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
