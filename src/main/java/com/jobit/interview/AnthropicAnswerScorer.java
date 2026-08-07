package com.jobit.interview;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.jobit.llm.LlmCallRecorder;
import com.jobit.llm.LlmException;
import com.jobit.llm.LlmFeature;
import com.jobit.llm.LlmModelConfig;
import com.jobit.llm.StructuredOutput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * LLM 기반 답변 채점 (docs/interview-practice-design.md §5).
 *
 * <p>구조화 출력으로 형태를 강제하고, 받은 값을 {@link AnswerScoreNormalizer}로 다듬은 뒤
 * 돌려준다. {@code AnthropicJdParser}와 같은 골격이되 <b>재검증의 성격이 다르다</b> —
 * 그쪽은 내용이 비면 재시도하지만, 여기는 범위 밖 인덱스를 버리고 점수를 자를 뿐 재시도하지
 * 않는다. 채점은 한 문항에 한 번씩 나가는 호출이라 재시도가 곧 비용이고, 인덱스 하나가
 * 이상하다고 멀쩡한 나머지를 버릴 이유가 없다.
 *
 * <p><b>API 키가 없으면 이 빈은 등록되지 않는다</b> — {@link AnswerScorerFallbackConfig}가
 * 자리를 대신한다 ({@code AnthropicJdParser}와 같은 배선).
 */
@Component
@ConditionalOnProperty(name = "anthropic.api-key")
@RequiredArgsConstructor
@Slf4j
public class AnthropicAnswerScorer implements AnswerScorer {

	private final AnthropicClient client;

	private final LlmCallRecorder callRecorder;

	@Override
	public Score score(Request request) {
		int outlineSize = request.answerOutline() == null ? 0 : request.answerOutline().size();
		if (outlineSize == 0) {
			throw new IllegalArgumentException("answerOutline is empty — 채점 기준이 없다");
		}

		// **답하지 않았으면 부르지 않는다.** 제한 시간이 있는 이상 자주 일어나는 정상 경로이고,
		// 채점할 내용이 없는데 호출하면 돈만 나간다.
		if (request.transcript() == null || request.transcript().isBlank()) {
			log.debug("답변이 비어 있어 LLM 을 호출하지 않는다");
			return AnswerScoreNormalizer.unanswered(outlineSize);
		}

		LlmModelConfig.FeatureConfig config = LlmModelConfig.of(LlmFeature.ANSWER_SCORING);
		AnswerScoreResponse response = call(request, config);

		return AnswerScoreNormalizer.normalize(response.score(), response.covered(), outlineSize,
				response.feedback());
	}

	private AnswerScoreResponse call(Request request, LlmModelConfig.FeatureConfig config) {
		// effort는 StructuredOutput.withEffort로 건다 — outputConfig(Class)가 effort를
		// 조용히 지우기 때문이다. 자세한 이유는 그쪽 주석 참고.
		StructuredMessageCreateParams<AnswerScoreResponse> params = StructuredOutput.withEffort(
				MessageCreateParams.builder()
					.model(config.model())
					.maxTokens(config.maxTokens())
					// thinking을 끄지 않는다 — LlmModelConfig 주석 참고.
					.thinking(ThinkingConfigAdaptive.builder().build())
					.outputConfig(AnswerScoreResponse.class)
					// 최초 시스템 프롬프트는 top-level system 이다 (AnthropicJdParser 주석 참고).
					.system(AnswerScorePrompts.SYSTEM)
					.addUserMessage(AnswerScorePrompts.userMessage(request.questionText(),
							request.answerOutline(), request.requirementText(),
							request.transcript())),
				config.effort());

		long startedAt = System.nanoTime();
		StructuredMessage<AnswerScoreResponse> message;
		try {
			message = client.messages().create(params);
		}
		catch (RateLimitException ex) {
			throw new LlmException(LlmException.Kind.RATE_LIMIT,
					"요청이 몰려 잠시 채점할 수 없습니다. 잠시 후 다시 시도해 주세요.", ex);
		}
		catch (AnthropicIoException ex) {
			throw new LlmException(LlmException.Kind.UPSTREAM,
					"채점 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.", ex);
		}
		catch (AnthropicServiceException ex) {
			throw new LlmException(LlmException.Kind.UPSTREAM,
					"채점 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", ex);
		}
		long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;

		callRecorder.record(LlmFeature.ANSWER_SCORING, config.model(), message.usage(), false,
				latencyMs);

		// **로그에 transcript 도 응답 본문도 남기지 않는다** — 개인 발화다 (스펙 §6).
		return message.content()
			.stream()
			.flatMap(block -> block.text().stream())
			.map(text -> text.text())
			.findFirst()
			.orElseThrow(() -> new LlmException(LlmException.Kind.INVALID_RESPONSE,
					"채점 결과를 받지 못했습니다. 잠시 후 다시 시도해 주세요."));
	}
}
