package com.jobit.jd;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobit.llm.LlmCallRecorder;
import com.jobit.llm.LlmFeature;
import com.jobit.llm.OllamaChatClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 실제 Ollama 를 한 번 호출해 JD 파싱 경로 전체를 확인한다.
 *
 * <p><b>왜 따로 두는가.</b> 나머지 테스트는 요청 조립({@link JdParseParamsTest})과
 * 빈 배선({@link JdParserWiringTest})만 본다. 즉 "요청을 이렇게 만든다"까지는 고정되어 있지만
 * <b>그 요청이 실제로 통하는지</b> — {@code format} 스키마가 GBNF 로 받아들여지는지,
 * {@code think} 조합이 유효한지, 응답이 {@link JdParseResponse}로 역직렬화되는지, 그리고
 * <b>이 크기의 모델이 실제로 쓸만한 파싱을 내는지</b> — 는 아무도 확인하지 않는다. 여기가 그
 * 한 칸이다.
 *
 * <p><b>Docker가 필요 없다.</b> {@link OllamaJdParser}를 직접 조립하고 {@link LlmCallRecorder}는
 * 기록을 삼키는 것으로 갈아끼운다. Spring 컨텍스트도 DB도 타지 않으므로 LLM 경로만 순수하게
 * 검증된다.
 *
 * <p><b>실행 방법</b> — 과금은 없지만 로컬 추론이 몇 분 걸릴 수 있어 명시적으로 켜야 돈다.
 * Ollama 가 떠 있고 모델을 받아 둔 상태여야 한다.
 *
 * <pre>{@code
 * ollama pull qwen3:14b
 * JOBIT_LLM_SMOKE=1 ./gradlew test --tests '*OllamaJdParserSmokeTest*' -i
 * }</pre>
 *
 * <p>스위치가 없으면 <b>건너뛴다</b>(실패가 아니다). 그래서 평소 {@code ./gradlew test}는 Ollama
 * 없이 그대로 돈다. 모델을 올리거나 프롬프트·스키마 파생을 고친 뒤에는 이걸 한 번 돌린다.
 */
@EnabledIfEnvironmentVariable(named = "JOBIT_LLM_SMOKE", matches = "(?i)1|true|on",
		disabledReason = "로컬 추론이 몇 분 걸린다. JOBIT_LLM_SMOKE=1 로 켠다.")
class OllamaJdParserSmokeTest {

	/** OS 환경변수로 다른 호스트를 볼 수 있게 해 둔다 — 기본은 로컬이다. */
	private static final String BASE_URL = System.getenv()
		.getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");

	/**
	 * 실제 공고와 비슷하되 짧게. 입력 토큰이 곧 추론 시간이라 필요 이상으로 키우지 않는다.
	 * 세 섹션(자격요건/우대사항/담당업무)을 모두 넣어 {@code kind} 분류까지 함께 확인한다.
	 */
	private static final String SAMPLE_JD = """
			[토스페이먼츠] 결제 플랫폼 백엔드 개발자 (경력)

			■ 자격요건
			- Java 또는 Kotlin 기반 백엔드 개발 경력 3년 이상
			- Spring Boot 기반 서비스 개발 및 운영 경험
			- RDBMS 스키마 설계와 쿼리 튜닝 경험

			■ 우대사항
			- 결제, 정산 등 금융 도메인 경험
			- Kubernetes 기반 운영 경험
			- 대용량 트래픽 처리 및 성능 개선 경험

			■ 담당업무
			- 결제 승인/취소 API 설계 및 개발
			- 정산 배치 파이프라인 운영
			- 장애 대응 및 모니터링 체계 개선

			열정적이고 함께 성장할 분을 찾습니다.
			""";

	@Test
	@DisplayName("실제 Ollama 호출로 JD가 요구사항 목록까지 파싱된다")
	void parsesRealJobPosting() {
		OllamaChatClient client = new OllamaChatClient(BASE_URL, 16_384);
		JdParser parser = new OllamaJdParser(client, new RecordingSpy());

		JdParser.ParsedJd parsed = parser.parse(SAMPLE_JD);

		System.out.println("company=" + parsed.company());
		System.out.println("title=" + parsed.title());
		System.out.println("parsed=" + parsed.parsedJson());
		parsed.requirements()
			.forEach(r -> System.out.printf("  [%s] %s %s%n", r.kind(), r.text(), r.keywords()));

		// 재검증(OllamaJdParser.validate)이 이미 막는 것들이지만, 여기서 한 번 더 본다.
		// 재검증이 잘못 느슨해지면 이 테스트가 먼저 깨져야 한다.
		assertThat(parsed.requirements()).as("요구사항이 하나도 없으면 캐시에 쓰레기가 굳는다")
			.isNotEmpty();
		assertThat(parsed.requirements()).allSatisfy(r -> {
			assertThat(r.kind()).isNotNull();
			assertThat(r.text()).isNotBlank();
		});

		// 이 공고는 세 섹션이 명확하므로 분류가 한쪽으로 몰리면 프롬프트나 모델 크기를 의심한다.
		assertThat(parsed.requirements()).extracting(JdParser.ParsedRequirement::kind)
			.as("자격요건/우대사항/담당업무가 섞여 나와야 한다")
			.contains(Requirement.Kind.REQUIRED, Requirement.Kind.PREFERRED,
					Requirement.Kind.RESPONSIBILITY);

		// parsed 는 jsonb 컬럼에 그대로 들어간다. 직렬화가 깨지면 저장이 깨진다.
		assertThat(parsed.parsedJson()).isNotBlank().startsWith("{");
	}

	/**
	 * DB 대신 콘솔로 흘린다. {@code REQUIRES_NEW} 트랜잭션이 필요 없으므로 Spring 없이 돈다.
	 * 추론 시간은 이 출력으로 눈으로 확인한다 — 실제 {@code llm_call_log} 적재는 통합 경로의 몫이다.
	 */
	private static final class RecordingSpy extends LlmCallRecorder {

		private RecordingSpy() {
			super(null);
		}

		@Override
		public void record(LlmFeature feature, String model, long input, long output,
				boolean cacheHit, long latencyMs) {
			System.out.printf("[llm] %s model=%s in=%d out=%d %dms%n", feature, model, input,
					output, latencyMs);
		}
	}
}
