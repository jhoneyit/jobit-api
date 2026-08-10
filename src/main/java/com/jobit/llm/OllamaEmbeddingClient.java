package com.jobit.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Ollama {@code /api/embed} 기반 임베딩 (스펙 §4.3 1단계).
 *
 * <p><b>제공자가 하나로 합쳐졌다.</b> 예전에는 이 기능만 OpenAI 였다 — Anthropic 에 임베딩 API 가
 * 없었기 때문이다. Ollama 는 생성과 임베딩을 같은 서버에서 주므로 그 예외가 사라졌고, 외부로
 * 나가는 호출이 하나도 남지 않았다.
 *
 * <p><b>차원이 1024 다.</b> {@code text-embedding-3-small} 의 1536 과 다르고, 이 값에 맞추려고
 * {@code resume_bullet.embedding} 을 {@code vector(1024)} 로 바꿨다 (Flyway V11). 1536 차원을 내는
 * 로컬 임베딩 모델이 사실상 없어서 스키마 쪽을 옮기는 편이 나았다. <b>모델을 바꾸면 차원부터
 * 확인한다</b> — 차원이 다르면 마이그레이션이 먼저고, 기존 벡터는 변환할 방법이 없다.
 *
 * <p><b>{@code index} 로 재정렬하지 않는다. 못 한다.</b> OpenAI 응답에는 원소마다 {@code index} 가
 * 있어 순서를 검증할 수 있었지만 Ollama 는 배열 위치가 곧 순서다. 그래서 남은 방어는
 * <b>개수 일치</b> 하나뿐이고, 그만큼 {@code ResumeService} 쪽의 "문장 순서와 벡터 순서가 어긋나면
 * 저장하지 않는다"가 더 중요해졌다.
 */
@Component
@ConditionalOnProperty(name = "ollama.base-url")
@Slf4j
public class OllamaEmbeddingClient implements EmbeddingClient {

	/**
	 * 한 번에 보낼 문장 수 상한.
	 *
	 * <p>OpenAI 시절보다 작다. 로컬 임베딩은 배치를 키운다고 왕복이 줄어드는 것 이상으로 빨라지지
	 * 않고, 큰 배치는 한 요청이 통째로 실패할 때 잃는 것만 커진다.
	 */
	private static final int BATCH_SIZE = 32;

	/**
	 * 임베딩은 생성보다 훨씬 가볍지만 <b>첫 호출에는 모델 로딩이 포함된다.</b> 30초로는 처음 한 번이
	 * 타임아웃으로 죽는다.
	 */
	private static final Duration TIMEOUT = Duration.ofMinutes(2);

	private final RestClient restClient;

	private final LlmCallRecorder callRecorder;

	private final String model;

	private final int dimensions;

	public OllamaEmbeddingClient(@Value("${ollama.base-url}") String baseUrl,
			@Value("${jobit.llm.ollama.embedding-model:qwen3-embedding:0.6b}") String model,
			@Value("${jobit.llm.ollama.embedding-dimensions:1024}") int dimensions,
			LlmCallRecorder callRecorder) {

		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(5));
		factory.setReadTimeout(TIMEOUT);

		this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
		this.callRecorder = callRecorder;
		this.model = model;
		this.dimensions = dimensions;

		log.info("Ollama 임베딩 클라이언트 준비됨 (model={} dim={})", model, dimensions);
	}

	@Override
	public int dimensions() {
		return dimensions;
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

		EmbedResponse response;
		try {
			response = restClient.post()
				.uri("/api/embed")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("model", model, "input", batch))
				.retrieve()
				.body(EmbedResponse.class);
		}
		catch (RestClientException ex) {
			log.warn("Ollama 임베딩 호출 실패 (model={}): {}", model, ex.toString());
			log.warn("`ollama pull {}` 을 했는지 확인하세요. 생성 모델과 별개로 받아야 합니다.", model);
			throw new LlmException(LlmException.Kind.UPSTREAM,
					"이력서를 분석하지 못했습니다. 잠시 후 다시 시도해 주세요.", ex);
		}
		long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;

		if (response == null || response.embeddings() == null
				|| response.embeddings().size() != batch.size()) {
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE, "이력서 분석 결과가 올바르지 않습니다.");
		}

		// 로컬이라 돈은 안 나가지만 토큰 수와 지연은 여전히 관측 대상이다 — 어느 기능이 느린지는
		// 비용 대시보드와 같은 장부에서 봐야 알 수 있다.
		callRecorder.record(LlmFeature.EMBEDDING, model, response.promptEvalCount(), 0, false,
				latencyMs);

		List<float[]> vectors = new ArrayList<>(batch.size());
		for (List<Double> raw : response.embeddings()) {
			vectors.add(toVector(raw));
		}
		return vectors;
	}

	private float[] toVector(List<Double> raw) {
		if (raw == null || raw.size() != dimensions) {
			log.warn("임베딩 차원이 다릅니다: 기대 {} / 실제 {}. 모델({})과 "
					+ "resume_bullet.embedding 의 vector(n) 이 어긋났습니다.", dimensions,
					raw == null ? "null" : raw.size(), model);
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE, "이력서 분석 결과가 올바르지 않습니다.");
		}
		float[] vector = new float[dimensions];
		for (int i = 0; i < dimensions; i++) {
			vector[i] = raw.get(i).floatValue();
		}
		return vector;
	}

	/** {@code embeddings} 는 입력과 같은 순서다 — 위 클래스 주석 참고. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	private record EmbedResponse(List<List<Double>> embeddings,
			@JsonProperty("prompt_eval_count") long promptEvalCount) {
	}
}
