package com.jobit.jd;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.jobit.llm.LlmFeature;
import com.jobit.llm.LlmModelConfig;
import com.jobit.llm.StructuredOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AnthropicJdParser}가 만드는 요청 파라미터를 고정한다.
 *
 * <p>SDK의 {@code outputConfig(Class)}는 구조화 출력 스키마를 세팅하면서 빌더 타입을 바꾸고,
 * {@code outputConfig(OutputConfig)}는 effort를 세팅한다. <b>둘을 같이 부르면 한쪽이 덮이는지가
 * 문서에 없다.</b> effort가 조용히 날아가면 파싱이 기본 effort(high)로 돌아 비용이 몇 배가 되고,
 * 반대로 스키마가 날아가면 구조화 출력이 통째로 꺼진다. 둘 다 요청에 실리는지 여기서 못박는다.
 */
class JdParseParamsTest {

	private static final String JD = "백엔드 개발자를 채용합니다. Java, Spring Boot 경험 필수.";

	private StructuredMessageCreateParams<JdParseResponse> buildParams() {
		LlmModelConfig.FeatureConfig config = LlmModelConfig.of(LlmFeature.JD_PARSE);
		return StructuredOutput.withEffort(MessageCreateParams.builder()
			.model(config.model())
			.maxTokens(config.maxTokens())
			.thinking(ThinkingConfigAdaptive.builder().build())
			.outputConfig(JdParseResponse.class)
			.system(JdParsePrompts.SYSTEM)
			.addUserMessage(JdParsePrompts.userMessage(JD)), config.effort());
	}

	@Test
	@DisplayName("시스템 프롬프트는 top-level system 으로 간다 — messages[0] 에 넣으면 400")
	void putsSystemPromptAtTopLevel() {
		MessageCreateParams raw = buildParams().rawParams();

		assertThat(raw.system())
			.as("addSystemMessage 는 messages[0] 에 role:\"system\" 을 넣는다. "
					+ "그쪽은 대화 중간용이라 첫 자리에 오면 API 가 400 을 준다")
			.isPresent();
		assertThat(raw.messages()).as("사용자 메시지 하나만 남아야 한다").hasSize(1);
	}

	@Test
	@DisplayName("effort와 구조화 출력 스키마가 함께 요청에 실린다")
	void carriesBothEffortAndSchema() {
		MessageCreateParams raw = buildParams().rawParams();

		OutputConfig outputConfig = raw.outputConfig().orElseThrow();

		assertThat(outputConfig.effort()).contains(OutputConfig.Effort.LOW);
		assertThat(outputConfig.format())
			.as("구조화 출력 스키마가 effort 설정에 덮이면 안 된다")
			.isPresent();
	}

	@Test
	@DisplayName("파싱은 저렴한 effort로 돌린다 — 구조화 추출이라 깊은 추론이 필요 없다")
	void usesLowEffortForParsing() {
		assertThat(LlmModelConfig.of(LlmFeature.JD_PARSE).effort())
			.isEqualTo(OutputConfig.Effort.LOW);
	}

	@Test
	@DisplayName("thinking을 끄지 않는다 — Opus 5에서 도구 호출이 텍스트로 새는 실패 모드가 있다")
	void keepsThinkingOn() {
		MessageCreateParams raw = buildParams().rawParams();

		assertThat(raw.thinking()).isPresent();
		assertThat(raw.thinking().orElseThrow().adaptive()).isPresent();
	}

	@Test
	@DisplayName("응답 타입이 구조화 출력 대상으로 잡힌다")
	void bindsOutputType() {
		assertThat(buildParams().outputType()).isEqualTo(JdParseResponse.class);
	}
}
