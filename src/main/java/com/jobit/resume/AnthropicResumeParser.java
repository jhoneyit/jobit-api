package com.jobit.resume;

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
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * LLM 기반 이력서 문장 분해 (스펙 §3.3).
 *
 * <p>{@code AnthropicJdParser} 와 같은 골격이다 — 구조화 출력으로 형태를 강제하고, 받은 값을
 * 서버에서 재검증한 뒤, 실패하면 재시도한다 (스펙 §6).
 *
 * <p><b>다만 재검증의 목적이 다르다.</b> JD 파싱은 "빈 결과가 캐시에 굳는 것"을 막는 게 목적이지만,
 * 이력서는 캐시가 없다 (개인 자산이라 재사용 대상이 아니다). 여기서 막는 것은 <b>빈 문장</b>이다 —
 * 빈 문장을 임베딩하면 의미 없는 벡터가 갭 분석의 후보로 올라온다.
 *
 * <p><b>이 클래스는 로그에 이력서 내용을 남기지 않는다</b> (스펙 §6). 실패해도 문장 수만 남긴다.
 */
@Component
@ConditionalOnProperty(name = "anthropic.api-key")
@RequiredArgsConstructor
@Slf4j
public class AnthropicResumeParser implements ResumeParser {

	/** {@code AnthropicJdParser} 와 같은 값. 3회를 넘기면 비용만 늘고 결과는 거의 달라지지 않는다. */
	private static final int MAX_ATTEMPTS = 3;

	private final AnthropicClient client;

	private final LlmCallRecorder callRecorder;

	@Override
	public ParsedResume parse(String rawText) {
		LlmModelConfig.FeatureConfig config = LlmModelConfig.of(LlmFeature.RESUME_PARSE);

		RuntimeException lastFailure = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return toParsedResume(callAndValidate(rawText, config, attempt));
			}
			catch (InvalidResponseException ex) {
				lastFailure = ex;
				log.warn("이력서 분해 응답이 검증에 실패했습니다 ({}/{}): {}", attempt, MAX_ATTEMPTS,
						ex.getMessage());
			}
		}

		throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
				"이력서를 분석하지 못했습니다. 경력 내용이 담겨 있는지 확인해 주세요.", lastFailure);
	}

	private ResumeParseResponse callAndValidate(String rawText,
			LlmModelConfig.FeatureConfig config, int attempt) {

		// effort 는 StructuredOutput.withEffort 로 건다 — outputConfig(Class) 가 effort 를
		// 조용히 지우기 때문이다. 자세한 이유는 그쪽 주석 참고.
		StructuredMessageCreateParams<ResumeParseResponse> params = StructuredOutput.withEffort(
				MessageCreateParams.builder()
					.model(config.model())
					.maxTokens(config.maxTokens())
					// thinking 을 끄지 않는다 — LlmModelConfig 주석 참고.
					.thinking(ThinkingConfigAdaptive.builder().build())
					.outputConfig(ResumeParseResponse.class)
					// 최초 시스템 프롬프트는 top-level system 이다 (AnthropicJdParser 주석 참고).
					.system(ResumeParsePrompts.SYSTEM)
					.addUserMessage(ResumeParsePrompts.userMessage(rawText)),
				config.effort());

		long startedAt = System.nanoTime();
		StructuredMessage<ResumeParseResponse> message;
		try {
			message = client.messages().create(params);
		}
		catch (RateLimitException ex) {
			throw new LlmException(LlmException.Kind.RATE_LIMIT,
					"요청이 몰려 잠시 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.", ex);
		}
		catch (AnthropicIoException ex) {
			throw new LlmException(LlmException.Kind.UPSTREAM,
					"이력서 분석 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.", ex);
		}
		catch (AnthropicServiceException ex) {
			throw new LlmException(LlmException.Kind.UPSTREAM,
					"이력서 분석 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", ex);
		}
		long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;

		// 재시도도 실제로 돈이 나가므로 시도마다 기록한다.
		callRecorder.record(LlmFeature.RESUME_PARSE, config.model(), message.usage(), false,
				latencyMs);

		ResumeParseResponse response = extractContent(message);
		validate(response, attempt);
		return response;
	}

	private ResumeParseResponse extractContent(StructuredMessage<ResumeParseResponse> message) {
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
	 * <p>빈 문장이 통과하면 그대로 임베딩되어 갭 분석의 후보 목록을 오염시킨다. 벡터는 사후에
	 * 눈으로 확인할 수 없으므로 들어가기 전에 막는다.
	 */
	private void validate(ResumeParseResponse response, int attempt) {
		if (response == null || response.bullets() == null || response.bullets().isEmpty()) {
			throw new InvalidResponseException("경험 문장이 하나도 없습니다");
		}
		for (ResumeParseResponse.RawBullet bullet : response.bullets()) {
			if (bullet.text() == null || bullet.text().isBlank()) {
				throw new InvalidResponseException("text 가 비어 있는 문장이 있습니다");
			}
		}
		log.debug("이력서 분해 검증 통과 (시도 {}): 문장 {}개", attempt, response.bullets().size());
	}

	private ParsedResume toParsedResume(ResumeParseResponse response) {
		List<ParsedBullet> bullets = new ArrayList<>();
		for (ResumeParseResponse.RawBullet raw : response.bullets()) {
			bullets.add(new ParsedBullet(blankToNull(raw.company()), blankToNull(raw.period()),
					raw.text().strip()));
		}
		return new ParsedResume(bullets);
	}

	/**
	 * 모델이 "없음"을 빈 문자열로 표현하는 경우가 있다. 컬럼에서 {@code null} 과 {@code ""} 가
	 * 섞이면 조회 조건이 둘을 따로 다뤄야 하므로 한쪽으로 모은다.
	 */
	private String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	/** 재시도 대상. 밖으로 나가지 않으므로 사용자 문구를 담지 않는다. */
	private static class InvalidResponseException extends RuntimeException {

		InvalidResponseException(String message) {
			super(message);
		}
	}
}
