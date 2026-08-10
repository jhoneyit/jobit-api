package com.jobit.jd;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
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

/**
 * LLM 기반 JD 파서 (스펙 §4.1 3단계).
 *
 * <p>구조화 출력으로 형태를 강제하고, 받은 값을 <b>서버에서 다시 검증</b>한 뒤, 실패하면 재시도한다
 * (스펙 §6 엔지니어링 체크리스트). 구조화 출력이 형태를 보장하더라도 재검증을 두는 이유는
 * "형태는 맞지만 내용이 비어 있는" 응답을 걸러내기 위해서다 — 요구사항 0개짜리 파싱 결과가
 * {@code content_hash} 캐시에 들어가면 그 쓰레기가 계속 재사용된다.
 *
 * <p><b>재검증이 Anthropic 시절보다 더 중요해졌다.</b> 저쪽의 구조화 출력은 서버가 스키마 준수를
 * 보장했지만, Ollama 는 {@code format} 을 GBNF 문법으로 바꿔 <b>토큰 단위로 제약</b>한다 — 형태는
 * 그래서 오히려 더 확실하지만, 형태를 맞추려다 내용이 비는 일은 작은 모델일수록 흔하다.
 *
 * <p><b>{@code ollama.base-url} 이 없으면 이 빈은 등록되지 않는다.</b> {@link JdParserFallbackConfig}
 * 의 폴백이 자리를 지켜 앱은 뜨고, 파싱을 호출하는 순간 명확한 예외가 난다. 설정 없이도 캐시·이력
 * 로직은 굴려볼 수 있어야 한다.
 */
@Component
@ConditionalOnProperty(name = "ollama.base-url")
@RequiredArgsConstructor
@Slf4j
public class OllamaJdParser implements JdParser {

	/** 검증 실패 시 총 시도 횟수. 3회를 넘기면 대기 시간만 늘고 결과는 거의 달라지지 않는다. */
	private static final int MAX_ATTEMPTS = 3;

	/**
	 * {@code job_posting.parsed} 직렬화와 응답 역직렬화 전용. <b>앱 전역 매퍼를 주입받지 않는다</b> —
	 * 전역 설정(네이밍 전략, 널 처리 등)이 바뀌면 DB에 저장되는 형식이 조용히 따라 바뀌고, 이미
	 * 저장된 행과 새 행의 모양이 달라진다. 저장 포맷은 이 클래스가 고정한다.
	 */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final OllamaChatClient client;

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

	/**
	 * 요청 조립.
	 *
	 * <p><b>테스트가 이걸 그대로 부를 수 있게 꺼내 두었다.</b> 테스트가 같은 조립을 복사해 두면
	 * 프로덕션만 고쳤을 때 테스트는 옛 요청을 검사하며 통과한다 ({@code OllamaAnswerScorer} 와 같은
	 * 이유).
	 */
	static OllamaChatClient.Request buildRequest(String rawText,
			LlmModelConfig.FeatureConfig config) {

		return new OllamaChatClient.Request(config.model(), JdParsePrompts.SYSTEM,
				JdParsePrompts.userMessage(rawText), JsonSchemas.of(JdParseResponse.class),
				config.effort(), config.maxTokens());
	}

	private JdParseResponse callAndValidate(String rawText, LlmModelConfig.FeatureConfig config,
			int attempt) {

		OllamaChatClient.Completion completion;
		try {
			completion = client.chat(buildRequest(rawText, config));
		}
		catch (OllamaChatClient.OllamaCallException ex) {
			throw new LlmException(LlmException.Kind.UPSTREAM,
					"공고 분석 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.", ex);
		}

		// 재시도도 실제로 시간을 쓰므로 시도마다 기록한다.
		callRecorder.record(LlmFeature.JD_PARSE, config.model(), completion.usage().inputTokens(),
				completion.usage().outputTokens(), false, completion.latencyMs());

		if (completion.truncated()) {
			// 잘린 JSON 은 아래 역직렬화에서 터진다. 원인을 여기서 이름 붙여 둔다.
			log.warn("응답이 출력 상한에 걸려 잘렸습니다 (maxTokens={})", config.maxTokens());
		}

		JdParseResponse response = read(completion.content());
		validate(response, attempt);
		return response;
	}

	/**
	 * 구조화 출력 JSON 을 record 로 읽는다.
	 *
	 * <p><b>실패를 재시도 대상으로 던진다.</b> 문법 제약이 있어도 출력이 상한에 걸려 잘리면 JSON 이
	 * 닫히지 않은 채로 온다 — 그건 다시 부르면 될 수도 있는 실패다.
	 */
	private JdParseResponse read(String content) {
		if (content == null || content.isBlank()) {
			throw new InvalidResponseException("응답에 본문이 없습니다");
		}
		try {
			return MAPPER.readValue(content, JdParseResponse.class);
		}
		catch (JacksonException ex) {
			throw new InvalidResponseException("응답이 JSON 이 아닙니다: " + ex.getOriginalMessage());
		}
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
		catch (JacksonException ex) {
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE, "공고 분석 결과를 저장하지 못했습니다.",
					ex);
		}
	}

	/** 재시도 대상. 밖으로 나가지 않으므로 사용자 문구를 담지 않는다. */
	private static class InvalidResponseException extends RuntimeException {

		InvalidResponseException(String message) {
			super(message);
		}
	}
}
