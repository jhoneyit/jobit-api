package com.jobit.interview;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.jobit.llm.LlmFeature;
import com.jobit.llm.LlmModelConfig;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AnthropicAnswerScorer}가 만드는 요청 파라미터를 고정한다.
 *
 * <p>{@code JdParseParamsTest}와 같은 이유다 — {@code outputConfig(Class)}가 effort를 조용히
 * 지우는 함정은 기능마다 따로 밟는다. <b>채점은 호출 수가 많은 유일한 기능이라</b>
 * (세션 1건 = 문항 수만큼) effort가 날아가 기본값(high)으로 돌면 비용이 다른 기능보다
 * 빠르게 샌다.
 */
class AnswerScoreParamsTest {

	private static final List<String> OUTLINE = List.of("격리 수준 4가지", "팬텀 리드", "실무 선택 기준");

	/**
	 * <b>프로덕션 조립을 그대로 부른다.</b> 여기서 요청을 다시 만들면, 프로덕션만 바뀌었을 때
	 * 테스트는 옛 형태를 검사하며 통과한다 — 형태를 고정하겠다는 테스트가 형태 변경을 놓친다.
	 */
	private StructuredMessageCreateParams<AnswerScoreResponse> buildParams() {
		return AnthropicAnswerScorer.buildParams(
				new AnswerScorer.Request("트랜잭션 격리 수준을 설명해 주세요.", OUTLINE, "RDBMS 트랜잭션 이해",
						"격리 수준은 네 가지가 있고요..."),
				LlmModelConfig.of(LlmFeature.ANSWER_SCORING));
	}

	@Test
	@DisplayName("effort와 구조화 출력 스키마가 함께 요청에 실린다")
	void carriesBothEffortAndSchema() {
		OutputConfig outputConfig = buildParams().rawParams().outputConfig().orElseThrow();

		assertThat(outputConfig.effort()).contains(OutputConfig.Effort.MEDIUM);
		assertThat(outputConfig.format())
			.as("구조화 출력 스키마가 effort 설정에 덮이면 안 된다")
			.isPresent();
	}

	@Test
	@DisplayName("채점은 갭 분석과 같은 effort — 둘 다 판정 작업이다")
	void usesMediumEffortLikeGapAnalysis() {
		assertThat(LlmModelConfig.of(LlmFeature.ANSWER_SCORING).effort())
			.isEqualTo(LlmModelConfig.of(LlmFeature.GAP_ANALYSIS).effort());
	}

	@Test
	@DisplayName("시스템 프롬프트는 top-level system 으로 간다 — messages[0] 에 넣으면 400")
	void putsSystemPromptAtTopLevel() {
		MessageCreateParams raw = buildParams().rawParams();

		assertThat(raw.system()).isPresent();
		assertThat(raw.messages()).hasSize(1);
	}

	@Test
	@DisplayName("시스템 프롬프트에 cache_control 이 실린다 — 빠지면 조용히 캐싱이 꺼진다")
	void marksSystemPromptForCaching() {
		MessageCreateParams raw = buildParams().rawParams();

		// 문자열 오버로드로 되돌리면 여기서 깨진다 — 그쪽은 cache_control 을 실을 수 없다.
		List<TextBlockParam> blocks = raw.system()
			.orElseThrow()
			.textBlockParams()
			.orElseThrow(() -> new AssertionError(
					"system 이 텍스트 블록이 아니다 — 문자열 오버로드는 cache_control 을 담지 못한다"));

		assertThat(blocks).hasSize(1);
		assertThat(blocks.getFirst().cacheControl())
			.as("캐시 표시가 없으면 오류 없이 매번 전체 요금을 낸다")
			.isPresent();
	}

	@Test
	@DisplayName("캐시 TTL 은 기본(5분)이다 — 1시간으로 올리면 LlmPricing 의 쓰기 배율이 틀어진다")
	void usesDefaultCacheTtl() {
		TextBlockParam block = buildParams().rawParams()
			.system()
			.orElseThrow()
			.textBlockParams()
			.orElseThrow()
			.getFirst();

		// LlmPricing.CACHE_WRITE_RATIO 가 1.25(=5분 TTL) 다. 1시간 TTL 은 2배라
		// 여기만 바꾸면 비용 기록이 조용히 어긋난다.
		assertThat(block.cacheControl().orElseThrow().ttl())
			.as("TTL 을 명시하면 LlmPricing 의 쓰기 배율도 함께 봐야 한다")
			.isEmpty();
	}

	@Test
	@DisplayName("thinking을 끄지 않는다")
	void keepsThinkingOn() {
		assertThat(buildParams().rawParams().thinking().orElseThrow().adaptive()).isPresent();
	}

	@Test
	@DisplayName("응답 타입이 구조화 출력 대상으로 잡힌다")
	void bindsOutputType() {
		assertThat(buildParams().outputType()).isEqualTo(AnswerScoreResponse.class);
	}
}
