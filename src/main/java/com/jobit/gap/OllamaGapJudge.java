package com.jobit.gap;

import com.jobit.llm.JsonSchemas;
import com.jobit.llm.LlmCallRecorder;
import com.jobit.llm.LlmException;
import com.jobit.llm.LlmFeature;
import com.jobit.llm.LlmModelConfig;
import com.jobit.llm.OllamaChatClient;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * LLM 기반 갭 판정 (스펙 §4.3 2단계).
 *
 * <p>{@code OllamaAnswerScorer} 와 같은 골격이되 <b>재시도 조건이 하나 있다</b> — 근거 없는
 * MET/WEAK({@link GapVerdictNormalizer#unverifiable})만 한 번 다시 묻는다. 그대로 정규화하면
 * MISSING 으로 강등되는데, 모델이 근거를 보고도 인덱스만 빠뜨린 경우라면 사용자에게 거짓
 * "근거 없음"이 나간다. 한 번의 재시도(수 초)는 그 오판정보다 싸다. 그 밖의 응답은 재시도하지
 * 않는다 — 분석 하나가 요구사항 수만큼 이 호출을 반복하므로, 채점과 같은 이유로 재시도가 곧
 * 사용자 대기 시간이다.
 *
 * <p><b>{@code ollama.base-url} 이 없으면 이 빈은 등록되지 않는다</b> —
 * {@link GapJudgeFallbackConfig} 가 자리를 대신한다.
 */
@Component
@ConditionalOnProperty(name = "ollama.base-url")
@RequiredArgsConstructor
@Slf4j
public class OllamaGapJudge implements GapJudge {

	/** 응답 역직렬화 전용. 전역 매퍼를 쓰지 않는 이유는 {@code OllamaJdParser} 주석 참고. */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final OllamaChatClient client;

	private final LlmCallRecorder callRecorder;

	@Override
	public Verdict judge(Request request) {
		if (request.candidates() == null || request.candidates().isEmpty()) {
			throw new IllegalArgumentException("candidates is empty — 후보 없는 판정은 호출부가 끊는다");
		}

		LlmModelConfig.FeatureConfig config = LlmModelConfig.of(LlmFeature.GAP_ANALYSIS);

		GapJudgeResponse response = call(request, config);
		if (GapVerdictNormalizer.unverifiable(response, request.candidates().size())) {
			log.info("근거 없는 {} 판정 — 한 번 다시 묻는다", response.status());
			response = call(request, config);
		}

		return GapVerdictNormalizer.normalize(response, request.candidates());
	}

	/** 요청 조립. 테스트가 그대로 부를 수 있게 꺼내 두었다 ({@code OllamaAnswerScorer} 와 같은 이유). */
	static OllamaChatClient.Request buildRequest(Request request,
			LlmModelConfig.FeatureConfig config) {

		List<String> texts = new ArrayList<>(request.candidates().size());
		for (Candidate candidate : request.candidates()) {
			texts.add(candidate.text());
		}

		return new OllamaChatClient.Request(config.model(), GapJudgePrompts.SYSTEM,
				GapJudgePrompts.userMessage(request.requirementText(), texts),
				JsonSchemas.of(GapJudgeResponse.class), config.effort(), config.maxTokens());
	}

	private GapJudgeResponse call(Request request, LlmModelConfig.FeatureConfig config) {
		OllamaChatClient.Completion completion;
		try {
			completion = client.chat(buildRequest(request, config));
		}
		catch (OllamaChatClient.OllamaCallException ex) {
			throw new LlmException(LlmException.Kind.UPSTREAM,
					"갭 분석 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.", ex);
		}

		callRecorder.record(LlmFeature.GAP_ANALYSIS, config.model(),
				completion.usage().inputTokens(), completion.usage().outputTokens(), false,
				completion.latencyMs());

		// **로그에 이력서 문장도 응답 본문도 남기지 않는다** — 개인 자산이다 (스펙 §6).
		if (completion.content() == null || completion.content().isBlank()) {
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
					"갭 분석 결과를 받지 못했습니다. 잠시 후 다시 시도해 주세요.");
		}
		try {
			return MAPPER.readValue(completion.content(), GapJudgeResponse.class);
		}
		catch (JacksonException ex) {
			// 예외 메시지에 응답 조각이 실릴 수 있고, rationale 은 이력서 문장을 인용할 수 있다.
			// 그래서 원인 예외를 붙이지 않는다 (OllamaAnswerScorer 와 같은 이유).
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
					"갭 분석 결과를 받지 못했습니다. 잠시 후 다시 시도해 주세요.");
		}
	}
}
