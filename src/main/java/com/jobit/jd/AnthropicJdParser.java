package com.jobit.jd;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobit.llm.AnthropicConfig;
import com.jobit.llm.LlmCallRecorder;
import com.jobit.llm.LlmException;
import com.jobit.llm.LlmFeature;
import com.jobit.llm.LlmModelConfig;
import com.jobit.llm.StructuredOutput;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * LLM 기반 JD 파서 (스펙 §4.1 3단계).
 *
 * <p>구조화 출력으로 형태를 강제하고, 받은 값을 <b>서버에서 다시 검증</b>한 뒤, 실패하면 재시도한다
 * (스펙 §6 엔지니어링 체크리스트). 구조화 출력이 형태를 보장하더라도 재검증을 두는 이유는
 * "형태는 맞지만 내용이 비어 있는" 응답을 걸러내기 위해서다 — 요구사항 0개짜리 파싱 결과가
 * {@code content_hash} 캐시에 들어가면 그 쓰레기가 계속 재사용된다.
 *
 * <p><b>API 키가 없으면 이 빈은 등록되지 않는다.</b> {@link AnthropicConfig}가 만드는
 * {@code AnthropicClient}가 같은 조건이라, 조건을 걸지 않으면 키 없는 환경에서 주입 대상을 찾지
 * 못해 앱이 아예 뜨지 않는다. 키 없이도 캐시·이력 로직은 굴려볼 수 있어야 하므로
 * {@link JdParserFallbackConfig}가 자리를 대신한다.
 */
@Component
@ConditionalOnProperty(name = "anthropic.api-key")
@RequiredArgsConstructor
@Slf4j
public class AnthropicJdParser implements JdParser {

	/** 검증 실패 시 총 시도 횟수. 3회를 넘기면 비용만 늘고 결과는 거의 달라지지 않는다. */
	private static final int MAX_ATTEMPTS = 3;

	/**
	 * {@code job_posting.parsed} 직렬화 전용. <b>앱 전역 매퍼를 주입받지 않는다</b> — 전역 설정
	 * (네이밍 전략, 널 처리 등)이 바뀌면 DB에 저장되는 형식이 조용히 따라 바뀌고, 이미 저장된
	 * 행과 새 행의 모양이 달라진다. 저장 포맷은 이 클래스가 고정한다.
	 */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final AnthropicClient client;

	private final LlmCallRecorder callRecorder;

	@Override
	public ParsedJd parse(String rawText) {
		LlmModelConfig.FeatureConfig config = LlmModelConfig.of(LlmFeature.JD_PARSE);

		RuntimeException lastFailure = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return toParsedJd(callAndValidate(rawText, config, attempt));
			}
			catch (InvalidResponseException ex) {
				lastFailure = ex;
				log.warn("JD 파싱 응답이 검증에 실패했습니다 ({}/{}): {}", attempt, MAX_ATTEMPTS,
						ex.getMessage());
			}
		}

		throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
				"공고를 분석하지 못했습니다. 공고 본문이 맞는지 확인해 주세요.", lastFailure);
	}

	private JdParseResponse callAndValidate(String rawText, LlmModelConfig.FeatureConfig config,
			int attempt) {
		// effort는 StructuredOutput.withEffort로 건다 — outputConfig(Class)가 effort를
		// 조용히 지우기 때문이다. 자세한 이유는 그쪽 주석 참고.
		StructuredMessageCreateParams<JdParseResponse> params = StructuredOutput.withEffort(
				MessageCreateParams.builder()
					.model(config.model())
					.maxTokens(config.maxTokens())
					// thinking을 끄지 않는다 — LlmModelConfig 주석 참고.
					.thinking(ThinkingConfigAdaptive.builder().build())
					.outputConfig(JdParseResponse.class)
					.addSystemMessage(JdParsePrompts.SYSTEM)
					.addUserMessage(JdParsePrompts.userMessage(rawText)),
				config.effort());

		long startedAt = System.nanoTime();
		StructuredMessage<JdParseResponse> message;
		try {
			message = client.messages().create(params);
		}
		catch (RateLimitException ex) {
			throw new LlmException(LlmException.Kind.RATE_LIMIT,
					"요청이 몰려 잠시 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.", ex);
		}
		catch (AnthropicIoException ex) {
			throw new LlmException(LlmException.Kind.UPSTREAM,
					"공고 분석 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.", ex);
		}
		catch (AnthropicServiceException ex) {
			throw new LlmException(LlmException.Kind.UPSTREAM,
					"공고 분석 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", ex);
		}
		long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;

		// 재시도도 실제로 돈이 나가므로 시도마다 기록한다.
		callRecorder.record(LlmFeature.JD_PARSE, config.model(), message.usage(), false, latencyMs);

		JdParseResponse response = extractContent(message);
		validate(response, attempt);
		return response;
	}

	private JdParseResponse extractContent(StructuredMessage<JdParseResponse> message) {
		return message.content()
			.stream()
			.flatMap(block -> block.text().stream())
			.map(text -> text.text())
			.findFirst()
			.orElseThrow(() -> new InvalidResponseException("응답에 본문이 없습니다"));
	}

	/**
	 * 서버측 재검증 (스펙 §6).
	 *
	 * <p>구조화 출력이 필드 존재는 보장하지만 <b>내용이 비어 있는 것은 막지 못한다.</b>
	 * 요구사항이 하나도 없는 결과는 캐시에 굳으면 계속 재사용되므로 여기서 끊는다.
	 */
	private void validate(JdParseResponse response, int attempt) {
		if (response == null || response.parsed() == null) {
			throw new InvalidResponseException("parsed 가 비어 있습니다");
		}
		List<JdParseResponse.RawRequirement> requirements = response.requirements();
		if (requirements == null || requirements.isEmpty()) {
			throw new InvalidResponseException("요구사항이 하나도 없습니다");
		}
		for (JdParseResponse.RawRequirement requirement : requirements) {
			if (requirement.kind() == null) {
				throw new InvalidResponseException("kind 가 없는 요구사항이 있습니다");
			}
			if (requirement.text() == null || requirement.text().isBlank()) {
				throw new InvalidResponseException("text 가 비어 있는 요구사항이 있습니다");
			}
		}
		log.debug("JD 파싱 검증 통과 (시도 {}): 요구사항 {}개", attempt, requirements.size());
	}

	private ParsedJd toParsedJd(JdParseResponse response) {
		JdParseResponse.ParsedMeta meta = response.parsed();

		List<ParsedRequirement> requirements = new ArrayList<>();
		for (JdParseResponse.RawRequirement raw : response.requirements()) {
			requirements.add(new ParsedRequirement(raw.text().strip(), raw.kind(),
					raw.keywords() == null ? List.of() : raw.keywords()));
		}

		return new ParsedJd(meta.company(), meta.title(), toJson(meta), requirements);
	}

	/** {@code job_posting.parsed}는 jsonb 컬럼이라 문자열로 직렬화해 넣는다. */
	private String toJson(JdParseResponse.ParsedMeta meta) {
		try {
			return MAPPER.writeValueAsString(meta);
		}
		catch (JsonProcessingException ex) {
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
					"공고 분석 결과를 저장하지 못했습니다.", ex);
		}
	}

	/** 재시도 대상. 밖으로 나가지 않으므로 사용자 문구를 담지 않는다. */
	private static class InvalidResponseException extends RuntimeException {

		InvalidResponseException(String message) {
			super(message);
		}
	}
}
