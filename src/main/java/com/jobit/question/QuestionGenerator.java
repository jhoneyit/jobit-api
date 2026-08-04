package com.jobit.question;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.jobit.jd.Requirement;
import com.jobit.llm.IncrementalArrayParser;
import com.jobit.llm.LlmException;
import com.jobit.llm.LlmFeature;
import com.jobit.llm.LlmModelConfig;
import com.jobit.llm.StructuredOutput;
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
 * <p><b>JD 파싱과 달리 스트리밍이다.</b> 질문 10개를 다 받으려면 십수 초가 걸리는데, 그동안 빈
 * 화면을 보여줄 수는 없다. 그래서 완성된 질문이 나올 때마다 콜백으로 흘려보낸다 —
 * {@link IncrementalArrayParser}가 그 판정을 한다.
 *
 * <p><b>구조화 출력을 스트리밍에 쓰는 방법.</b> {@code outputConfig(Class)}는 타입 안전한
 * {@code StructuredMessageCreateParams}를 만들지만 그쪽에는 스트리밍 경로가 없다. 그래서
 * 클래스에서 <b>파생된 스키마만 꺼내</b> 일반 {@link MessageCreateParams}에 얹는다. 스키마 정의를
 * 손으로 다시 쓰지 않아도 되고, {@link StructuredOutput}이 effort까지 함께 챙긴다.
 *
 * <p>API 키가 없으면 이 빈은 등록되지 않는다 ({@code AnthropicJdParser}와 같은 조건).
 */
@Component
@ConditionalOnProperty(name = "anthropic.api-key")
@RequiredArgsConstructor
@Slf4j
public class QuestionGenerator {

	private final AnthropicClient client;

	/**
	 * 질문을 생성하며 완성되는 대로 {@code onQuestion}에 넘긴다.
	 *
	 * @param onQuestion 검증을 통과한 질문마다 호출된다. 여기서 던지면 스트림이 중단된다
	 * @return 실제로 사용된 모델과 사용량. 호출자가 {@code llm_call_log}에 기록한다
	 */
	public Result generate(Map<String, Object> parsedMeta, List<Requirement> requirements,
			Consumer<QuestionGenResponse.RawQuestion> onQuestion) {

		LlmModelConfig.FeatureConfig config = LlmModelConfig.of(LlmFeature.QUESTION_GEN);

		// 파생된 스키마만 꺼내 스트리밍용 파라미터에 얹는다 — 위 클래스 주석 참고.
		OutputConfig outputConfig = StructuredOutput
			.withEffort(MessageCreateParams.builder()
				.model(config.model())
				.maxTokens(config.maxTokens())
				.outputConfig(QuestionGenResponse.class)
				.addUserMessage("x"), config.effort())
			.rawParams()
			.outputConfig()
			.orElseThrow(() -> new IllegalStateException("구조화 출력 스키마를 만들지 못했습니다"));

		MessageCreateParams params = MessageCreateParams.builder()
			.model(config.model())
			.maxTokens(config.maxTokens())
			// thinking을 끄지 않는다 — LlmModelConfig 주석 참고.
			.thinking(ThinkingConfigAdaptive.builder().build())
			.outputConfig(outputConfig)
			.system(QuestionGenPrompts.SYSTEM)
			.addUserMessage(QuestionGenPrompts.userMessage(parsedMeta, requirements))
			.build();

		IncrementalArrayParser parser = new IncrementalArrayParser("questions");
		long startedAt = System.nanoTime();
		int emitted = 0;
		Usage usage = Usage.EMPTY;

		try (StreamResponse<RawMessageStreamEvent> stream = client.messages()
			.createStreaming(params)) {

			for (RawMessageStreamEvent event : (Iterable<RawMessageStreamEvent>) stream.stream()
				::iterator) {

				usage = usage.merge(event);

				String delta = textDelta(event);
				if (delta == null) {
					continue;
				}
				for (String raw : parser.push(delta)) {
					QuestionGenResponse.RawQuestion q = IncrementalArrayParser.read(raw,
							QuestionGenResponse.RawQuestion.class);
					if (q != null && q.isValid()) {
						emitted++;
						onQuestion.accept(q);
					}
				}
			}
		}
		catch (RateLimitException ex) {
			throw wrap(emitted, LlmException.Kind.RATE_LIMIT,
					"요청이 몰려 잠시 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.", ex);
		}
		catch (AnthropicIoException ex) {
			throw wrap(emitted, LlmException.Kind.UPSTREAM,
					"질문 생성 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.", ex);
		}
		catch (AnthropicServiceException ex) {
			throw wrap(emitted, LlmException.Kind.UPSTREAM,
					"질문 생성 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", ex);
		}

		long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;

		if (emitted == 0) {
			log.warn("질문을 하나도 파싱하지 못했습니다. 응답 앞부분: {}",
					parser.rawBuffer().substring(0, Math.min(300, parser.rawBuffer().length())));
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
					"질문을 생성하지 못했습니다. 잠시 후 다시 시도해 주세요.", null);
		}
		if (parser.isTruncated()) {
			// 부분 결과는 이미 사용자에게 갔다. 버리지 않고 기록만 남긴다.
			log.warn("응답이 잘렸습니다 — 질문 {}개만 생성됐습니다 (maxTokens={})", emitted,
					config.maxTokens());
		}

		return new Result(config.model(), usage, latencyMs, emitted);
	}

	/**
	 * 이미 사용자에게 흘려보낸 질문이 있으면 예외를 삼킬지 여부를 호출자가 정해야 한다.
	 * 여기서는 정보만 실어 보낸다.
	 */
	private LlmException wrap(int emitted, LlmException.Kind kind, String message,
			RuntimeException cause) {
		log.warn("질문 생성 스트림 중단 (이미 {}개 전송): {}", emitted, cause.toString());
		return new LlmException(kind, message, cause);
	}

	/** {@code content_block_delta} 의 텍스트만 꺼낸다. thinking 델타는 무시한다. */
	private String textDelta(RawMessageStreamEvent event) {
		return event.contentBlockDelta()
			.flatMap(e -> e.delta().text())
			.map(t -> t.text())
			.orElse(null);
	}

	/**
	 * 스트림 전체의 사용량.
	 *
	 * <p>입력 토큰은 {@code message_start}에, 출력 토큰은 {@code message_delta}에 실려 온다.
	 * 한쪽만 보면 비용이 통째로 틀린다.
	 */
	public record Usage(long inputTokens, long outputTokens, long cacheReadTokens,
			long cacheCreationTokens) {

		static final Usage EMPTY = new Usage(0, 0, 0, 0);

		Usage merge(RawMessageStreamEvent event) {
			var start = event.messageStart();
			if (start.isPresent()) {
				var u = start.get().message().usage();
				return new Usage(u.inputTokens(), u.outputTokens(),
						u.cacheReadInputTokens().orElse(0L),
						u.cacheCreationInputTokens().orElse(0L));
			}
			var delta = event.messageDelta();
			if (delta.isPresent()) {
				// message_delta 의 usage 는 출력 토큰만 갱신한다. 입력은 message_start 값을 유지한다.
				var u = delta.get().usage();
				return new Usage(inputTokens, u.outputTokens(), cacheReadTokens,
						cacheCreationTokens);
			}
			return this;
		}
	}

	public record Result(String model, Usage usage, long latencyMs, int count) {
	}
}
