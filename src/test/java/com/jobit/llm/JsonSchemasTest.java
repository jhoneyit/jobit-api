package com.jobit.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link JsonSchemas} 의 파생 결과를 고정한다.
 *
 * <p><b>이 테스트가 왜 필요해졌나.</b> 예전에는 Anthropic SDK 가 클래스에서 스키마를 파생시켰고,
 * 그게 맞는지는 저쪽이 책임졌다. 이제 우리 코드다 — 그리고 <b>여기서 나는 실수는 전부 조용하다.</b>
 * 설명이 빠지면 프롬프트의 절반이 사라진 채로 나가고, 널 허용이 뒤집히면 모델이 "없음"을 표현할
 * 방법을 잃어 <b>없는 값을 지어낸다.</b> 둘 다 예외 없이 결과 품질로만 드러난다.
 *
 * <p>"근거 없는 내용을 지어내지 않는다"는 이 제품의 작업 원칙이라, 널 허용 규칙은 스타일이 아니라
 * 제품 규칙에 걸려 있다.
 */
class JsonSchemasTest {

	/** 실제로 모델에게 나가는 응답 타입 전부. 새 기능이 늘면 여기에 더한다. */
	private static final List<Class<?>> PRODUCTION_TYPES = List.of(com.jobit.jd.JdParseResponse.class,
			com.jobit.resume.ResumeParseResponse.class,
			com.jobit.interview.AnswerScoreResponse.class,
			com.jobit.question.QuestionGenResponse.class,
			com.jobit.gap.GapJudgeResponse.class, com.jobit.gap.RewriteResponse.class,
			com.jobit.video.VideoReportResponse.class, com.jobit.video.ChunkSummaryResponse.class);

	@Nested
	@DisplayName("파생 규칙")
	class DerivationRules {

		@Test
		@DisplayName("모든 필드가 required 이고 추가 필드는 막힌다")
		void marksEveryFieldRequired() {
			Map<String, Object> schema = JsonSchemas.of(Sample.class);

			assertThat(schema).containsEntry("type", "object")
				.containsEntry("additionalProperties", false);
			assertThat(schema.get("required")).asInstanceOf(LIST)
				.containsExactly("name", "count", "boxedCount", "kind", "tags", "boundedTags",
						"nested");
		}

		/**
		 * <b>키가 있다는 것과 값이 널이 아니라는 것은 다른 이야기다.</b> 전부 {@code required} 이면서
		 * 참조 타입은 널을 허용하는 것이 모순이 아닌 이유 — 모델은 반드시 키를 내되 "모름"을 널로
		 * 표현할 수 있어야 한다.
		 */
		@Test
		@DisplayName("참조 타입은 널을 허용한다 — 모델이 '없음'을 표현할 수 있어야 한다")
		void allowsNullForReferenceTypes() {
			Map<String, Object> properties = propertiesOf(Sample.class);

			assertThat(properties.get("name")).asInstanceOf(MAP)
				.containsEntry("type", List.of("string", "null"));
			assertThat(properties.get("boxedCount")).asInstanceOf(MAP)
				.containsEntry("type", List.of("integer", "null"));
			assertThat(properties.get("tags")).asInstanceOf(MAP)
				.containsEntry("type", List.of("array", "null"));
			assertThat(properties.get("nested")).asInstanceOf(MAP)
				.containsEntry("type", List.of("object", "null"));
		}

		@Test
		@DisplayName("원시 타입은 널을 허용하지 않는다 — Java 타입이 그대로 규칙이다")
		void forbidsNullForPrimitives() {
			assertThat(propertiesOf(Sample.class).get("count")).asInstanceOf(MAP)
				.containsEntry("type", "integer");
		}

		/**
		 * 열거형만 예외다. {@code enum} 목록에 널을 끼우면 Ollama 가 GBNF 로 바꿀 때 지저분해지고,
		 * 이 제품의 열거형(요구사항 종류·질문 분류)은 "모름"이 의미를 갖지 않는다.
		 */
		@Test
		@DisplayName("열거형은 값 목록을 싣고 널을 허용하지 않는다")
		void listsEnumValues() {
			assertThat(propertiesOf(Sample.class).get("kind")).asInstanceOf(MAP)
				.containsEntry("type", "string")
				.containsEntry("enum", List.of("A", "B"));
		}

		@Test
		@DisplayName("List 의 원소 타입까지 따라 들어간다")
		void derivesListItems() {
			@SuppressWarnings("unchecked")
			Map<String, Object> tags = (Map<String, Object>) propertiesOf(Sample.class).get("tags");

			assertThat(tags.get("items")).asInstanceOf(MAP)
				.containsEntry("type", List.of("string", "null"));
		}

		@Test
		@DisplayName("중첩 record 도 같은 규칙으로 펼쳐진다")
		void derivesNestedRecords() {
			@SuppressWarnings("unchecked")
			Map<String, Object> nested = (Map<String, Object>) propertiesOf(Sample.class)
				.get("nested");

			assertThat(nested).containsEntry("additionalProperties", false);
			assertThat(nested.get("properties")).asInstanceOf(MAP).containsKey("inner");
		}

		@Test
		@DisplayName("record 가 아니면 거부한다 — 조용히 빈 스키마를 만들지 않는다")
		void rejectsNonRecords() {
			assertThatThrownBy(() -> JsonSchemas.of(String.class))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	/**
	 * <b>상한 없는 배열은 문법이 종료를 강제하지 못한다.</b> Ollama 는 스키마를 GBNF 로 바꾸므로
	 * {@code maxItems} 가 없으면 "원소를 하나 더" 가 언제나 합법이고, 모델이 반복에 빠지면 출력
	 * 상한까지 간다 — 2026-08-13 JD 파싱이 같은 문구를 100번 내며 4,000토큰을 태운 실패다.
	 */
	@Nested
	@DisplayName("배열 상한")
	class ArrayBounds {

		@Test
		@DisplayName("@MaxItems 가 maxItems 로 실린다")
		void carriesMaxItems() {
			assertThat(propertiesOf(Sample.class).get("boundedTags")).asInstanceOf(MAP)
				.containsEntry("maxItems", 3);
		}

		@Test
		@DisplayName("애너테이션이 없으면 상한을 지어내지 않는다")
		void omitsMaxItemsWhenUnannotated() {
			assertThat(propertiesOf(Sample.class).get("tags")).asInstanceOf(MAP)
				.doesNotContainKey("maxItems");
		}

		/**
		 * 조용히 무시하면 "상한을 걸어 뒀다"고 믿는 채로 상한 없는 스키마가 나간다 —
		 * 이 애너테이션이 막으려는 상황이 정확히 그것이다.
		 */
		@Test
		@DisplayName("배열이 아닌 필드에 붙으면 거부한다")
		void rejectsMaxItemsOnNonArray() {
			assertThatThrownBy(() -> JsonSchemas.of(BadBound.class))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("notAList");
		}

		/**
		 * 새 배열 필드를 상한 없이 추가하면 여기서 걸린다. 중첩까지 훑는 이유는 실제로 터진 자리가
		 * 최상위가 아니라 {@code JdParseResponse.parsed.keywords} 였기 때문이다.
		 */
		@Test
		@DisplayName("실제 응답 타입의 모든 배열에 상한이 있다 — 중첩된 것까지")
		void productionArraysAreBounded() {
			for (Class<?> type : PRODUCTION_TYPES) {
				assertBounded(type.getSimpleName(), JsonSchemas.of(type));
			}
		}

		@SuppressWarnings("unchecked")
		private void assertBounded(String path, Map<String, Object> schema) {
			if (isArray(schema)) {
				assertThat(schema)
					.as("%s 에 @MaxItems 가 없다 — 모델이 반복에 빠지면 출력 상한까지 간다", path)
					.containsKey("maxItems");
				assertBounded(path + "[]", (Map<String, Object>) schema.get("items"));
				return;
			}
			if (schema.get("properties") instanceof Map<?, ?> properties) {
				properties.forEach((field, nested) -> assertBounded(path + "." + field,
						(Map<String, Object>) nested));
			}
		}

		private boolean isArray(Map<String, Object> schema) {
			Object type = schema.get("type");
			return type instanceof List<?> types ? types.contains("array") : "array".equals(type);
		}
	}

	/**
	 * <b>여기가 이 테스트의 핵심이다.</b> {@code @JsonPropertyDescription} 은 장식이 아니라
	 * 프롬프트의 일부라는 규약이 응답 record 들에 걸려 있다. 그 규약은 이 파생이 설명을 실어
	 * 날라야만 성립하는데, 애너테이션의 {@code @Target} 에 {@code RECORD_COMPONENT} 가 없으면
	 * 리플렉션 경로에 따라 <b>전부 널로 나온다</b> — 그래도 스키마는 멀쩡해 보인다.
	 */
	@Nested
	@DisplayName("설명 전달")
	class Descriptions {

		@Test
		@DisplayName("@JsonPropertyDescription 이 description 으로 실린다")
		void carriesDescriptions() {
			assertThat(propertiesOf(Sample.class).get("name")).asInstanceOf(MAP)
				.containsEntry("description", "이름. 없으면 null");
		}

		@Test
		@DisplayName("중첩 record 안의 설명도 실린다")
		void carriesNestedDescriptions() {
			@SuppressWarnings("unchecked")
			Map<String, Object> nested = (Map<String, Object>) propertiesOf(Sample.class)
				.get("nested");
			@SuppressWarnings("unchecked")
			Map<String, Object> properties = (Map<String, Object>) nested.get("properties");

			assertThat(properties.get("inner")).asInstanceOf(MAP)
				.containsEntry("description", "안쪽 값");
		}

		/**
		 * 실제 응답 타입 넷을 한 번에 훑는다. 새 필드를 설명 없이 추가하면 여기서 걸린다 —
		 * 그 필드는 모델에게 이름만 전달되고 의미는 전달되지 않는다.
		 */
		@Test
		@DisplayName("실제 응답 타입의 모든 최상위 필드에 설명이 붙어 있다")
		void productionResponsesAreFullyDescribed() {
			for (Class<?> type : PRODUCTION_TYPES) {
				propertiesOf(type).forEach((field, schema) -> assertThat(schema).asInstanceOf(MAP)
					.as("%s.%s 에 @JsonPropertyDescription 이 없다 — 모델은 이름만 보고 추측한다",
							type.getSimpleName(), field)
					.containsKey("description"));
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> propertiesOf(Class<?> type) {
		return (Map<String, Object>) JsonSchemas.of(type).get("properties");
	}

	private record Sample(@JsonPropertyDescription("이름. 없으면 null") String name, int count,
			Integer boxedCount, Kind kind, List<String> tags, @MaxItems(3) List<String> boundedTags,
			Nested nested) {

		enum Kind {

			A, B

		}

		record Nested(@JsonPropertyDescription("안쪽 값") String inner) {
		}
	}

	private record BadBound(@MaxItems(3) String notAList) {
	}
}
