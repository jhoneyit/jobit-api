package com.jobit.gap;

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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * LLM 기반 리라이트 (스펙 §4.4).
 *
 * <p>{@code OllamaJdParser} 처럼 <b>검증 실패를 재시도한다</b> — 단 2회까지다. 여기는 thinking 을
 * 켜는 기능이라({@code Effort.HIGH}, 문장 품질이 곧 제품 가치) 시도 하나가 수십 초고,
 * 사용자가 버튼을 누르고 기다리는 자리라 3회는 과하다. 재시도 대상은
 * {@link RewriteNormalizer#problem} 이 잡는 것들이다 — 특히 <b>지어낸 숫자</b>는 프롬프트 지시를
 * 모델이 어겼다는 뜻이라, 같은 요청을 다시 샘플링하면 대개 지켜진다.
 *
 * <p><b>{@code ollama.base-url} 이 없으면 이 빈은 등록되지 않는다</b> —
 * {@link RewriterFallbackConfig} 가 자리를 대신한다.
 */
@Component
@ConditionalOnProperty(name = "ollama.base-url")
@RequiredArgsConstructor
@Slf4j
public class OllamaRewriter implements Rewriter {

	/** 시도 하나가 수십 초(thinking)라 3회는 사용자를 너무 오래 세운다. */
	private static final int MAX_ATTEMPTS = 2;

	/** 응답 역직렬화 전용. 전역 매퍼를 쓰지 않는 이유는 {@code OllamaJdParser} 주석 참고. */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final OllamaChatClient client;

	private final LlmCallRecorder callRecorder;

	@Override
	public Suggestion rewrite(Request request) {
		LlmModelConfig.FeatureConfig config = LlmModelConfig.of(LlmFeature.REWRITE);

		String lastProblem = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			RewriteResponse response = call(request, config);

			String problem = RewriteNormalizer.problem(response, request.bulletText(),
					request.requirementText());
			if (problem == null) {
				return new Suggestion(response.suggested().strip(), response.reason().strip());
			}
			lastProblem = problem;
			log.warn("리라이트 응답이 검증에 실패했습니다 ({}/{}): {}", attempt, MAX_ATTEMPTS, problem);
		}

		log.warn("리라이트 재시도 소진: {}", lastProblem);
		throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
				"수정안을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.");
	}

	/** 요청 조립. 테스트가 그대로 부를 수 있게 꺼내 두었다 ({@code OllamaGapJudge} 와 같은 이유). */
	static OllamaChatClient.Request buildRequest(Request request,
			LlmModelConfig.FeatureConfig config) {

		return new OllamaChatClient.Request(config.model(), RewritePrompts.SYSTEM,
				RewritePrompts.userMessage(request.requirementText(), request.rationale(),
						request.bulletText()),
				JsonSchemas.of(RewriteResponse.class), config.effort(), config.maxTokens());
	}

	private RewriteResponse call(Request request, LlmModelConfig.FeatureConfig config) {
		OllamaChatClient.Completion completion;
		try {
			completion = client.chat(buildRequest(request, config));
		}
		catch (OllamaChatClient.OllamaCallException ex) {
			throw new LlmException(LlmException.Kind.UPSTREAM,
					"리라이트 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.", ex);
		}

		callRecorder.record(LlmFeature.REWRITE, config.model(), completion.usage().inputTokens(),
				completion.usage().outputTokens(), false, completion.latencyMs());

		// **로그에 이력서 문장도 응답 본문도 남기지 않는다** — 개인 자산이다 (스펙 §6).
		if (completion.content() == null || completion.content().isBlank()) {
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
					"수정안을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.");
		}
		try {
			return MAPPER.readValue(completion.content(), RewriteResponse.class);
		}
		catch (JacksonException ex) {
			// 예외 메시지에 응답 조각(= 이력서 문장을 담은 수정안)이 실릴 수 있어 원인을 붙이지 않는다.
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
					"수정안을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.");
		}
	}
}
