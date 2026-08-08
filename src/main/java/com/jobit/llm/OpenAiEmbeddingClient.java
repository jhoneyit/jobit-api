package com.jobit.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * OpenAI {@code text-embedding-3-small} 기반 임베딩 (스펙 §4.3 1단계).
 *
 * <p><b>SDK 를 넣지 않고 {@link RestClient} 로 직접 부른다.</b> 쓰는 엔드포인트가
 * {@code POST /v1/embeddings} 하나뿐이라 의존성 하나를 더 지고 갈 이유가 없다 — 요청은 필드 두
 * 개, 응답은 배열 하나다.
 *
 * <p><b>모델 선택의 근거는 차원 수다.</b> {@code resume_bullet.embedding} 이 {@code vector(1536)}
 * 로 이미 잡혀 있고 {@code text-embedding-3-small} 이 정확히 1536차원이라 마이그레이션 없이 맞는다.
 * (Anthropic 이 안내하는 Voyage 쪽 현행 모델은 대부분 1024차원이라 컬럼 변경이 따라온다.)
 *
 * <p><b>API 키가 없으면 이 빈은 등록되지 않는다.</b> {@link EmbeddingClientFallbackConfig} 가
 * 자리를 지키고, 호출하면 명확한 예외를 던진다. {@code AnthropicJdParser} 와 같은 이유이며,
 * 같은 함정도 공유한다: <b>{@code .env} 에는
 * {@code openai.api-key} 라고 소문자 프로퍼티 이름으로 적어야 한다.</b> {@code OPENAI_API_KEY}
 * 완화 바인딩은 OS 환경변수 소스에만 적용된다.
 */
@Component
@ConditionalOnProperty(name = "openai.api-key")
@Slf4j
public class OpenAiEmbeddingClient implements EmbeddingClient {

	private static final String MODEL = "text-embedding-3-small";

	/** {@code resume_bullet.embedding vector(1536)} 과 같은 값이어야 한다. */
	private static final int DIMENSIONS = 1536;

	/**
	 * 한 번에 보낼 문장 수 상한. OpenAI 는 배열 입력을 받지만 요청 본문 크기 제한이 있고,
	 * 이력서 하나가 이 수를 넘는 일은 드물다. 넘으면 나눠 보낸다.
	 */
	private static final int BATCH_SIZE = 96;

	/** 임베딩은 생성보다 훨씬 빠르다. 여기서 오래 매달리면 업로드 응답이 통째로 늦어진다. */
	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	private final RestClient restClient;

	private final LlmCallRecorder callRecorder;

	public OpenAiEmbeddingClient(@Value("${openai.api-key}") String apiKey,
			@Value("${openai.base-url:https://api.openai.com/v1}") String baseUrl,
			LlmCallRecorder callRecorder) {

		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(TIMEOUT);
		factory.setReadTimeout(TIMEOUT);

		this.restClient = RestClient.builder()
			.baseUrl(baseUrl)
			.requestFactory(factory)
			.defaultHeader("Authorization", "Bearer " + apiKey)
			.build();
		this.callRecorder = callRecorder;

		log.info("OpenAI 임베딩 클라이언트 준비됨 (model={} dim={})", MODEL, DIMENSIONS);
	}

	@Override
	public int dimensions() {
		return DIMENSIONS;
	}

	@Override
	public List<float[]> embedAll(List<String> texts) {
		if (texts == null || texts.isEmpty()) {
			return List.of();
		}

		List<float[]> result = new ArrayList<>(texts.size());
		for (int from = 0; from < texts.size(); from += BATCH_SIZE) {
			int to = Math.min(from + BATCH_SIZE, texts.size());
			result.addAll(embedBatch(texts.subList(from, to)));
		}
		return result;
	}

	private List<float[]> embedBatch(List<String> batch) {
		long startedAt = System.nanoTime();

		EmbeddingResponse response;
		try {
			response = restClient.post()
				.uri("/embeddings")
				.contentType(MediaType.APPLICATION_JSON)
				.body(new EmbeddingRequest(MODEL, batch))
				.retrieve()
				.body(EmbeddingResponse.class);
		}
		catch (RestClientException ex) {
			// 상태 코드로 갈라 봐야 사용자가 할 수 있는 일은 같다 — 잠시 후 재시도.
			throw new LlmException(LlmException.Kind.UPSTREAM,
					"이력서를 분석하지 못했습니다. 잠시 후 다시 시도해 주세요.", ex);
		}
		long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;

		if (response == null || response.data() == null || response.data().size() != batch.size()) {
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
					"이력서 분석 결과가 올바르지 않습니다.");
		}

		// 임베딩도 돈이 나간다. 다른 LLM 호출과 같은 장부에 남겨야 비용 대시보드가 진실을 말한다.
		long promptTokens = response.usage() == null ? 0 : response.usage().promptTokens();
		callRecorder.record(LlmFeature.EMBEDDING, MODEL, promptTokens, 0, 0, 0, false, latencyMs);

		// **index 로 정렬한다.** OpenAI 는 순서를 보장한다고 문서화하지만, 순서가 어긋나면
		// 문장과 벡터가 뒤바뀐 채 조용히 저장되어 갭 분석이 엉뚱한 근거를 집는다.
		// 사후에 알아채기 어려운 종류의 버그라 반환값을 믿지 않는다.
		List<Datum> sorted = new ArrayList<>(response.data());
		sorted.sort(Comparator.comparingInt(Datum::index));

		List<float[]> vectors = new ArrayList<>(sorted.size());
		for (Datum datum : sorted) {
			vectors.add(toVector(datum));
		}
		return vectors;
	}

	private float[] toVector(Datum datum) {
		List<Double> raw = datum.embedding();
		if (raw == null || raw.size() != DIMENSIONS) {
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
					"이력서 분석 결과가 올바르지 않습니다.");
		}
		float[] vector = new float[DIMENSIONS];
		for (int i = 0; i < DIMENSIONS; i++) {
			vector[i] = raw.get(i).floatValue();
		}
		return vector;
	}

	private record EmbeddingRequest(String model, List<String> input) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record EmbeddingResponse(List<Datum> data, Usage usage) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Datum(int index, List<Double> embedding) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Usage(
			@com.fasterxml.jackson.annotation.JsonProperty("prompt_tokens") long promptTokens) {
	}
}
