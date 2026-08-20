package com.jobit.interview;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.jobit.llm.JsonSchemas;
import com.jobit.llm.LlmCallRecorder;
import com.jobit.llm.LlmException;
import com.jobit.llm.LlmFeature;
import com.jobit.llm.LlmModelConfig;
import com.jobit.llm.OllamaChatClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * LLM 기반 답변 채점 (docs/interview-practice-design.md §5).
 *
 * <p>구조화 출력으로 형태를 강제하고, 받은 값을 {@link AnswerScoreNormalizer}로 다듬어 돌려준다.
 * {@code OllamaJdParser}와 같은 골격이되 <b>재검증의 성격이 다르다</b> — 그쪽은 내용이 비면
 * 재시도하지만, 여기는 범위 밖 인덱스를 버리고 점수를 자를 뿐 재시도하지 않는다. 채점은 한 문항에
 * 한 번씩 나가는 호출이라 재시도가 곧 대기 시간이고, 인덱스 하나가 이상하다고 멀쩡한 나머지를
 * 버릴 이유가 없다.
 *
 * <h2>프롬프트 캐싱이 사라졌다</h2>
 *
 * <p>예전에는 시스템 프롬프트에 {@code cache_control} 을 달아 이 기능에서만 46%를 아꼈다. Ollama
 * 에는 그에 해당하는 API 가 없다 — 대신 <b>같은 프리픽스를 KV 캐시에서 자동으로 재사용</b>한다.
 * 즉 절감은 여전히 일어나지만 우리가 표시할 것도, 응답에서 확인할 것도 없다.
 *
 * <p><b>대신 모델이 메모리에 남아 있어야 한다.</b> Ollama 는 유휴 시간이 지나면 모델을 내리고,
 * 그러면 KV 캐시도 함께 사라져 세션 중간에 갑자기 느려진다. 한 세션이 문항 수만큼 연달아
 * 호출하는 이 기능이 그 영향을 가장 크게 받는다 — {@code OLLAMA_KEEP_ALIVE} 를 넉넉히 잡는다
 * (CLAUDE.md "로컬 LLM" 참고).
 *
 * <p><b>{@code ollama.base-url} 이 없으면 이 빈은 등록되지 않는다</b> —
 * {@link AnswerScorerFallbackConfig}가 자리를 대신한다.
 */
@Component
@ConditionalOnProperty(name = "ollama.base-url")
@RequiredArgsConstructor
@Slf4j
public class OllamaAnswerScorer implements AnswerScorer {

	/** 응답 역직렬화 전용. 전역 매퍼를 쓰지 않는 이유는 {@code OllamaJdParser} 주석 참고. */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final OllamaChatClient client;

	private final LlmCallRecorder callRecorder;

	@Override
	public Score score(Request request) {
		int outlineSize = request.answerOutline() == null ? 0 : request.answerOutline().size();
		if (outlineSize == 0) {
			throw new IllegalArgumentException("answerOutline is empty — 채점 기준이 없다");
		}

		// **답하지 않았으면 부르지 않는다.** 제한 시간이 있는 이상 자주 일어나는 정상 경로이고,
		// 채점할 내용이 없는데 호출하면 GPU 시간만 쓴다.
		if (request.transcript() == null || request.transcript().isBlank()) {
			log.debug("답변이 비어 있어 LLM 을 호출하지 않는다");
			return AnswerScoreNormalizer.unanswered(outlineSize);
		}

		LlmModelConfig.FeatureConfig config = LlmModelConfig.of(LlmFeature.ANSWER_SCORING);
		AnswerScoreResponse response = call(request, config);

		return AnswerScoreNormalizer.normalize(response.score(), response.covered(), outlineSize,
				response.feedback(), request.transcript());
	}

	/**
	 * 요청 조립.
	 *
	 * <p><b>테스트가 이걸 그대로 부를 수 있게 꺼내 두었다.</b> 테스트가 같은 조립을 복사해 두면
	 * 프로덕션만 고쳤을 때 테스트는 옛 요청을 검사하며 통과한다 — 요청 형태를 고정하겠다는
	 * 테스트가 정작 형태가 바뀐 것을 못 잡는다.
	 */
	static OllamaChatClient.Request buildRequest(Request request,
			LlmModelConfig.FeatureConfig config) {

		return new OllamaChatClient.Request(config.model(), AnswerScorePrompts.SYSTEM,
				AnswerScorePrompts.userMessage(request.questionText(), request.answerOutline(),
						request.requirementText(), request.transcript()),
				JsonSchemas.of(AnswerScoreResponse.class), config.effort(), config.maxTokens());
	}

	private AnswerScoreResponse call(Request request, LlmModelConfig.FeatureConfig config) {
		OllamaChatClient.Completion completion;
		try {
			completion = client.chat(buildRequest(request, config));
		}
		catch (OllamaChatClient.OllamaCallException ex) {
			throw new LlmException(LlmException.Kind.UPSTREAM,
					"채점 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.", ex);
		}

		callRecorder.record(LlmFeature.ANSWER_SCORING, config.model(),
				completion.usage().inputTokens(), completion.usage().outputTokens(), false,
				completion.latencyMs());

		// **로그에 transcript 도 응답 본문도 남기지 않는다** — 개인 발화다 (스펙 §6).
		if (completion.content() == null || completion.content().isBlank()) {
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
					"채점 결과를 받지 못했습니다. 잠시 후 다시 시도해 주세요.");
		}
		try {
			return MAPPER.readValue(completion.content(), AnswerScoreResponse.class);
		}
		catch (JacksonException ex) {
			// 예외 메시지에 응답 조각이 실릴 수 있고, 그 안에는 답변을 인용한 피드백이 들어 있다.
			// 그래서 원인 예외를 붙이지 않는다.
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
					"채점 결과를 받지 못했습니다. 잠시 후 다시 시도해 주세요.");
		}
	}
}
