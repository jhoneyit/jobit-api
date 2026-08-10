package com.jobit.question;

import com.jobit.jd.Requirement;
import com.jobit.llm.IncrementalArrayParser;
import com.jobit.llm.JsonSchemas;
import com.jobit.llm.LlmException;
import com.jobit.llm.LlmFeature;
import com.jobit.llm.LlmModelConfig;
import com.jobit.llm.OllamaChatClient;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 질문 생성 LLM 호출 (스펙 §4.2).
 *
 * <p><b>JD 파싱과 달리 스트리밍이다.</b> 질문 10개를 다 받으려면 오래 걸리는데 — 로컬에서는 더
 * 그렇다 — 그동안 빈 화면을 보여줄 수는 없다. 그래서 완성된 질문이 나올 때마다 콜백으로
 * 흘려보낸다. {@link IncrementalArrayParser}가 그 판정을 한다.
 *
 * <p><b>스트리밍이 단순해졌다.</b> Anthropic 쪽은 {@code message_start} / {@code content_block_delta}
 * / {@code message_delta} 를 갈라 읽고 사용량을 두 군데서 합쳐야 했지만, Ollama 는 NDJSON 한 줄이
 * 하나의 조각이고 토큰 수는 마지막 줄에 한 번에 온다. 그 처리는 전부
 * {@link OllamaChatClient#stream} 안에 있고 여기는 텍스트 조각만 받는다.
 *
 * <p><b>구조화 출력은 스트리밍에서도 그대로 쓴다.</b> 예전에는 SDK 의 타입 안전한 파라미터에
 * 스트리밍 경로가 없어 파생된 스키마만 꺼내 옮겨 담는 우회가 필요했다. Ollama 의 {@code format} 은
 * {@code stream} 과 무관하게 같은 자리에 들어가므로 그 우회가 사라졌다.
 *
 * <p>{@code ollama.base-url} 이 없으면 이 빈은 등록되지 않는다 ({@code OllamaJdParser} 와 같은 조건).
 */
@Component
@ConditionalOnProperty(name = "ollama.base-url")
@RequiredArgsConstructor
@Slf4j
public class QuestionGenerator {

	private final OllamaChatClient client;

	/**
	 * 질문을 생성하며 완성되는 대로 {@code onQuestion}에 넘긴다.
	 *
	 * @param onQuestion 검증을 통과한 질문마다 호출된다. 여기서 던지면 스트림이 중단된다
	 * @return 실제로 사용된 모델과 사용량. 호출자가 {@code llm_call_log}에 기록한다
	 */
	public Result generate(Map<String, Object> parsedMeta, List<Requirement> requirements,
			Consumer<QuestionGenResponse.RawQuestion> onQuestion) {

		LlmModelConfig.FeatureConfig config = LlmModelConfig.of(LlmFeature.QUESTION_GEN);

		OllamaChatClient.Request request = new OllamaChatClient.Request(config.model(),
				QuestionGenPrompts.SYSTEM, QuestionGenPrompts.userMessage(parsedMeta, requirements),
				JsonSchemas.of(QuestionGenResponse.class), config.effort(), config.maxTokens());

		IncrementalArrayParser parser = new IncrementalArrayParser("questions");
		// 배열로 감싼 이유는 람다 안에서 증가시켜야 하기 때문이다. 스트림 소비는 한 스레드다.
		int[] emitted = { 0 };

		OllamaChatClient.Completion completion;
		try {
			completion = client.stream(request, delta -> {
				for (String raw : parser.push(delta)) {
					QuestionGenResponse.RawQuestion question = IncrementalArrayParser.read(raw,
							QuestionGenResponse.RawQuestion.class);
					if (question != null && question.isValid()) {
						emitted[0]++;
						onQuestion.accept(question);
					}
				}
			});
		}
		catch (OllamaChatClient.OllamaCallException ex) {
			// 이미 사용자에게 흘려보낸 질문이 있으면 예외를 삼킬지는 호출자가 정한다.
			// 여기서는 정보만 실어 보낸다.
			log.warn("질문 생성 스트림 중단 (이미 {}개 전송): {}", emitted[0], ex.toString());
			throw new LlmException(LlmException.Kind.UPSTREAM,
					"질문 생성 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.", ex);
		}

		if (emitted[0] == 0) {
			log.warn("질문을 하나도 파싱하지 못했습니다. 응답 앞부분: {}",
					parser.rawBuffer().substring(0, Math.min(300, parser.rawBuffer().length())));
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
					"질문을 생성하지 못했습니다. 잠시 후 다시 시도해 주세요.", null);
		}
		if (completion.truncated() || parser.isTruncated()) {
			// 부분 결과는 이미 사용자에게 갔다. 버리지 않고 기록만 남긴다.
			log.warn("응답이 잘렸습니다 — 질문 {}개만 생성됐습니다 (maxTokens={})", emitted[0],
					config.maxTokens());
		}

		return new Result(config.model(), completion.usage(), completion.latencyMs(), emitted[0]);
	}

	public record Result(String model, OllamaChatClient.Usage usage, long latencyMs, int count) {
	}
}
