package com.jobit.llm;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Ollama {@code /api/chat} 클라이언트. 이 서버의 모든 생성 호출이 여기를 지난다.
 *
 * <p><b>SDK 를 넣지 않고 {@link RestClient} 로 직접 부른다.</b> 쓰는 엔드포인트가 두 개뿐이고
 * ({@code /api/chat}, 임베딩 쪽의 {@code /api/embed}) 요청·응답이 평평한 JSON 이라, 의존성을 하나
 * 더 지고 갈 이유가 없다. 걷어낸 Anthropic SDK 가 해 주던 일 중 실제로 아쉬운 것은 스키마 파생
 * 하나였고 그건 {@link JsonSchemas} 가 대신한다.
 *
 * <p><b>OpenAI 호환 엔드포인트({@code /v1/chat/completions})를 쓰지 않는다.</b> 네이티브 쪽만
 * {@code think} 로 Qwen3 의 thinking 을 끌 수 있고, 스트리밍이 SSE 가 아니라 NDJSON 이라 파싱이
 * 단순하며, 토큰 수가 {@code prompt_eval_count}/{@code eval_count} 로 매 응답에 실려 온다.
 *
 * <h2>기억해 둘 함정</h2>
 *
 * <p><b>1. {@code num_ctx} 를 반드시 준다.</b> Ollama 의 기본 컨텍스트는 모델의 최대치가 아니라
 * 훨씬 작은 값이고, 넘치면 <b>에러가 아니라 조용한 앞부분 잘림</b>으로 처리된다. JD 본문이나
 * 이력서가 길면 앞쪽이 사라진 채로 그럴듯한 결과가 나온다 — 사후에 알아채기 가장 어려운 실패다.
 * 그래서 요청마다 명시하고, 실제 소비량이 상한에 닿으면 경고를 남긴다.
 *
 * <p><b>2. 탐욕적 디코딩(temperature=0)을 쓰지 않는다.</b> Qwen3 는 이 설정에서 같은 문장을 끝없이
 * 반복하는 알려진 실패 모드가 있다. 구조화 출력이라 온도를 낮추고 싶어지는 게 자연스러운데,
 * 그 직관을 따르면 안 되는 모델이다. 값은 Qwen 이 권장하는 조합을 thinking 여부로 갈라 쓴다.
 *
 * <p><b>3. 프롬프트 캐싱 API 가 없다.</b> Anthropic 의 {@code cache_control} 에 해당하는 것이
 * 없고, 대신 Ollama 가 <b>같은 프리픽스를 자동으로 KV 캐시에서 재사용</b>한다. 사용자가 할 수 있는
 * 일은 모델이 메모리에서 내려가지 않게 두는 것뿐이다 ({@code OLLAMA_KEEP_ALIVE}). 그래서 채점
 * 쪽에 있던 캐시 표시는 사라졌고, 비용 절감분을 계산하던 로직도 함께 없앴다.
 */
@Component
@ConditionalOnProperty(name = "ollama.base-url")
@Slf4j
public class OllamaChatClient {

	/**
	 * NDJSON 한 줄과 응답 본문 전용 매퍼.
	 *
	 * <p><b>앱 전역 매퍼를 주입받지 않는다.</b> 전역 설정(네이밍 전략 등)이 바뀌면 Ollama 가 주는
	 * {@code prompt_eval_count} 같은 스네이크 케이스 필드를 못 찾게 되고, 그러면 토큰 수가 조용히
	 * 0 으로 기록된다. 이 클래스가 읽는 형식은 Ollama 가 정하지 우리 설정이 정하지 않는다.
	 */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * 로컬 추론은 느리다. 네트워크 왕복이 아니라 생성 시간 자체가 이 값 안에 들어와야 한다 —
	 * thinking 을 켠 질문 생성이 몇 분씩 걸릴 수 있다.
	 */
	private static final Duration TIMEOUT = Duration.ofMinutes(10);

	private final RestClient restClient;

	private final int numCtx;

	public OllamaChatClient(@Value("${ollama.base-url}") String baseUrl,
			@Value("${jobit.llm.ollama.num-ctx:16384}") int numCtx) {

		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(5));
		factory.setReadTimeout(TIMEOUT);

		this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
		this.numCtx = numCtx;

		log.info("Ollama 클라이언트 준비됨 (baseUrl={} numCtx={})", baseUrl, numCtx);
	}

	/** 한 번에 받아 오는 호출. 구조화 출력이므로 {@link Completion#content()} 는 JSON 문자열이다. */
	public Completion chat(Request request) {
		long startedAt = System.nanoTime();

		JsonNode response;
		try {
			response = restClient.post()
				.uri("/api/chat")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body(request, false))
				.retrieve()
				.body(JsonNode.class);
		}
		catch (RestClientException ex) {
			throw failed(request, ex);
		}
		long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;

		if (response == null) {
			throw new OllamaCallException("Ollama 응답이 비어 있습니다", null);
		}

		Usage usage = usageOf(response);
		warnIfContextTight(request, usage);
		return new Completion(response.path("message").path("content").asString(""), usage,
				latencyMs, "length".equals(response.path("done_reason").asString(null)));
	}

	/**
	 * 스트리밍 호출. 생성되는 대로 텍스트 조각을 {@code onDelta} 로 넘긴다.
	 *
	 * <p><b>SSE 가 아니라 NDJSON 이다.</b> 한 줄이 곧 하나의 JSON 객체이고, {@code done:true} 인
	 * 마지막 줄에만 토큰 수가 실린다. {@code event:} / {@code data:} 접두사가 없어 Anthropic 쪽보다
	 * 다루기 쉽다.
	 *
	 * <p><b>{@code thinking} 필드는 넘기지 않는다.</b> thinking 을 켜면 사고 과정이
	 * {@code message.thinking} 으로 따로 오는데, 이걸 {@code content} 와 섞으면 JSON 조각을
	 * 읽는 쪽({@code IncrementalArrayParser})이 통째로 망가진다.
	 *
	 * @param onDelta 텍스트 조각마다 호출된다. 여기서 던지면 스트림이 중단된다
	 */
	public Completion stream(Request request, Consumer<String> onDelta) {
		long startedAt = System.nanoTime();

		Completion completion;
		try {
			// exchange 는 람다가 던진 IOException 을 RestClientException 으로 바꿔 내보낸다.
			// 그래서 여기서 IOException 을 따로 잡지 않는다 (잡으면 컴파일이 안 된다).
			completion = restClient.post()
				.uri("/api/chat")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body(request, true))
				.exchange((httpRequest,
						httpResponse) -> readStream(httpResponse.getBody(), onDelta));
		}
		catch (RestClientException ex) {
			throw failed(request, ex);
		}

		Completion result = completion.withLatency((System.nanoTime() - startedAt) / 1_000_000);
		warnIfContextTight(request, result.usage());
		return result;
	}

	private Completion readStream(java.io.InputStream body, Consumer<String> onDelta)
			throws IOException {

		Usage usage = Usage.EMPTY;
		boolean truncated = false;

		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(body, StandardCharsets.UTF_8))) {

			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				JsonNode event = MAPPER.readTree(line);

				// 에러는 200 본문 안에 실려 오기도 한다 — 스트림을 열고 나서 모델을 못 찾는 경우다.
				String error = event.path("error").asString(null);
				if (error != null) {
					throw new OllamaCallException("Ollama 스트림 오류: " + error, null);
				}

				String delta = event.path("message").path("content").asString("");
				if (!delta.isEmpty()) {
					onDelta.accept(delta);
				}

				if (event.path("done").asBoolean(false)) {
					usage = usageOf(event);
					truncated = "length".equals(event.path("done_reason").asString(null));
				}
			}
		}
		return new Completion("", usage, 0, truncated);
	}

	/**
	 * 요청 본문.
	 *
	 * <p><b>record 가 아니라 {@link Map} 으로 만든다.</b> Ollama 가 받는 키 이름
	 * ({@code num_predict}, {@code num_ctx})은 우리 것이 아니라 저쪽 규약이라, 전역 매퍼의 네이밍
	 * 전략이 바뀌어도 흔들리면 안 된다. 문자열로 박아 두는 편이 그 사실을 그대로 드러낸다.
	 *
	 * <p><b>테스트가 부를 수 있게 열어 두었다.</b> 이 맵의 내용은 전부 조용히 틀릴 수 있는 것들이다 —
	 * {@code num_ctx} 가 빠지면 입력이 잘리고, {@code think} 가 뒤집히면 응답이 느려지거나 스키마를
	 * 어기고, 온도가 0 이 되면 반복에 빠진다. 셋 다 예외를 내지 않는다.
	 */
	Map<String, Object> body(Request request, boolean stream) {
		List<Map<String, String>> messages = new ArrayList<>();
		if (request.system() != null && !request.system().isBlank()) {
			messages.add(Map.of("role", "system", "content", request.system()));
		}
		messages.add(Map.of("role", "user", "content", request.user()));

		// Qwen 권장 조합. thinking 여부로 갈린다 — 위 클래스 주석의 "탐욕적 디코딩" 항목 참고.
		boolean think = request.effort().think();
		Map<String, Object> options = new LinkedHashMap<>();
		options.put("num_ctx", numCtx);
		options.put("num_predict", request.numPredict());
		options.put("temperature", think ? 0.6 : 0.7);
		options.put("top_p", think ? 0.95 : 0.8);
		options.put("top_k", 20);

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", request.model());
		body.put("messages", messages);
		body.put("format", request.schema());
		body.put("think", think);
		body.put("stream", stream);
		body.put("options", options);
		return body;
	}

	private Usage usageOf(JsonNode response) {
		return new Usage(response.path("prompt_eval_count").asLong(0),
				response.path("eval_count").asLong(0));
	}

	/**
	 * 컨텍스트가 빠듯했는지 사후에 알린다.
	 *
	 * <p>Ollama 는 입력이 {@code num_ctx} 를 넘어도 에러를 주지 않고 앞을 잘라 낸다. 잘렸다는
	 * 사실이 응답 어디에도 없으므로, <b>소비량이 상한에 닿았다는 것으로 추정하는 수밖에 없다.</b>
	 * 결과가 이상한데 원인을 못 찾을 때 이 경고가 첫 단서가 된다.
	 */
	private void warnIfContextTight(Request request, Usage usage) {
		long consumed = usage.inputTokens() + usage.outputTokens();
		if (consumed >= numCtx * 0.95) {
			log.warn("컨텍스트가 거의 찼습니다 — 입력이 잘렸을 수 있습니다 "
					+ "(model={} in={} out={} numCtx={}). jobit.llm.ollama.num-ctx 를 올리거나 "
					+ "이 기능의 maxTokens 를 낮추세요.", request.model(), usage.inputTokens(),
					usage.outputTokens(), numCtx);
		}
	}

	/**
	 * 실패를 하나의 예외로 모은다.
	 *
	 * <p><b>사용자 문구는 여기서 만들지 않는다.</b> "무엇을 하다 실패했는지"는 호출부만 알고
	 * (공고 분석·이력서 분석·채점), docs/api.md 는 그 문구를 사용자에게 그대로 보여주기로 정해
	 * 두었다. 여기서는 운영자가 볼 단서만 로그로 남긴다.
	 */
	private OllamaCallException failed(Request request, Exception cause) {
		log.warn("Ollama 호출 실패 (model={}): {}", request.model(), cause.toString());
		log.warn("확인 순서: (1) ollama serve 가 떠 있는가 (2) `ollama pull {}` 을 했는가 "
				+ "(3) 이 모델이 thinking 을 지원하는가 — think 를 실은 요청은 지원하지 않는 모델에서 "
				+ "400 이 난다", request.model());
		return new OllamaCallException("Ollama 호출에 실패했습니다: " + request.model(), cause);
	}

	/**
	 * @param schema     {@link JsonSchemas#of} 로 만든 스키마. {@code format} 으로 나간다
	 * @param numPredict 출력 토큰 상한. {@code num_ctx} 안에서 입력과 자리를 나눠 쓴다
	 */
	public record Request(String model, String system, String user, Map<String, Object> schema,
			Effort effort, long numPredict) {
	}

	/** {@code prompt_eval_count} / {@code eval_count}. 캐시 관련 항목이 없다 — 로컬에는 그 개념이 없다. */
	public record Usage(long inputTokens, long outputTokens) {

		public static final Usage EMPTY = new Usage(0, 0);
	}

	/**
	 * @param content   구조화 출력 JSON 문자열. 스트리밍 호출에서는 비어 있다 (조각으로 이미 나갔다)
	 * @param truncated {@code done_reason=length} — {@code num_predict} 에 걸려 잘렸다
	 */
	public record Completion(String content, Usage usage, long latencyMs, boolean truncated) {

		Completion withLatency(long latencyMs) {
			return new Completion(content, usage, latencyMs, truncated);
		}
	}

	/**
	 * Ollama 에 닿지 못했거나 저쪽이 거부했다.
	 *
	 * <p>호출부가 이 하나만 잡아 자기 문구로 바꾼다. Anthropic 시절에는 레이트 리밋·연결·서비스
	 * 오류를 갈라 잡았지만, 로컬 추론에는 <b>레이트 리밋이 없고</b> 나머지 둘은 사용자가 할 수 있는
	 * 일이 같아서 (잠시 후 재시도) 나눌 이유가 사라졌다.
	 */
	public static class OllamaCallException extends RuntimeException {

		public OllamaCallException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
