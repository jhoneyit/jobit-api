package com.jobit.jd;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobit.llm.Effort;
import com.jobit.llm.JsonSchemas;
import com.jobit.llm.LlmFeature;
import com.jobit.llm.LlmModelConfig;
import com.jobit.llm.OllamaChatClient;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OllamaJdParser}가 만드는 요청을 고정한다.
 *
 * <p><b>지키는 것이 달라졌다.</b> Anthropic 시절 이 테스트가 막던 것은 SDK 의 함정이었다 —
 * {@code outputConfig(Class)} 가 effort 를 조용히 지워 파싱이 비싼 설정으로 도는 것. 그 API 가
 * 사라졌으니 그 함정도 사라졌고, 대신 <b>요청을 우리가 직접 조립하게 되면서 생긴 것들</b>을 막는다:
 * 스키마를 빠뜨리기, 시스템 프롬프트를 사용자 메시지에 섞기, 출력 상한을 잘못 넘기기.
 *
 * <p>셋 다 예외를 내지 않는다. 스키마가 없으면 모델이 자유 텍스트를 뱉고, 프롬프트를 섞으면
 * 결과가 미묘하게 나빠질 뿐이며, 출력 상한이 틀리면 긴 공고에서만 잘린다.
 */
class JdParseParamsTest {

	private static final String JD = "백엔드 개발자를 채용합니다. Java, Spring Boot 경험 필수.";

	/**
	 * <b>프로덕션 조립을 그대로 부른다.</b> 여기서 요청을 다시 만들면, 프로덕션만 바뀌었을 때
	 * 테스트는 옛 형태를 검사하며 통과한다 — 형태를 고정하겠다는 테스트가 형태 변경을 놓친다.
	 */
	private OllamaChatClient.Request buildRequest() {
		return OllamaJdParser.buildRequest(JD, LlmModelConfig.of(LlmFeature.JD_PARSE));
	}

	@Test
	@DisplayName("시스템 프롬프트가 사용자 메시지와 분리되어 실린다")
	void keepsSystemPromptSeparate() {
		OllamaChatClient.Request request = buildRequest();

		assertThat(request.system()).isEqualTo(JdParsePrompts.SYSTEM);
		assertThat(request.user()).as("공고 본문은 사용자 메시지 쪽이다").contains(JD);
		assertThat(request.user()).as("둘을 한 문자열로 합치면 안 된다").doesNotContain(JdParsePrompts.SYSTEM);
	}

	@Test
	@DisplayName("구조화 출력 스키마가 요청에 실린다 — 빠지면 모델이 자유 텍스트를 뱉는다")
	void carriesSchema() {
		Map<String, Object> schema = buildRequest().schema();

		assertThat(schema).isEqualTo(JsonSchemas.of(JdParseResponse.class));
		assertThat(schema).containsEntry("type", "object");
		assertThat(schema.get("properties")).asInstanceOf(
				org.assertj.core.api.InstanceOfAssertFactories.MAP)
			.containsKeys("parsed", "requirements");
	}

	@Test
	@DisplayName("파싱은 thinking 을 끈다 — 구조화 추출이라 깊은 추론이 필요 없다")
	void doesNotThink() {
		assertThat(buildRequest().effort()).isEqualTo(Effort.LOW);
		assertThat(buildRequest().effort().think())
			.as("여기서 thinking 을 켜면 파싱 한 번이 몇 배로 느려진다")
			.isFalse();
	}

	/**
	 * <b>출력 상한은 컨텍스트 창을 입력과 나눠 쓴다.</b> 기본 {@code num-ctx} 가 16384 이므로
	 * 이 값이 그에 육박하면 긴 공고의 앞부분이 조용히 잘린다 ({@code OllamaChatClient} 주석).
	 */
	@Test
	@DisplayName("출력 상한이 설정에서 그대로 넘어가고, 컨텍스트 창의 절반을 넘지 않는다")
	void carriesOutputLimit() {
		LlmModelConfig.FeatureConfig config = LlmModelConfig.of(LlmFeature.JD_PARSE);

		assertThat(buildRequest().numPredict()).isEqualTo(config.maxTokens());
		assertThat(config.maxTokens())
			.as("입력이 쓸 자리가 남아야 한다 — 기본 num-ctx 는 16384 다")
			.isLessThanOrEqualTo(8_192);
	}

	@Test
	@DisplayName("모델이 기본 모델이다 — 기능마다 다른 모델을 물리면 메모리를 서로 뺏는다")
	void usesDefaultModel() {
		assertThat(buildRequest().model()).isEqualTo(LlmModelConfig.DEFAULT_MODEL);
	}
}
