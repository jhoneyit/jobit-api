package com.jobit.llm;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java record 에서 JSON Schema 를 파생시킨다.
 *
 * <p><b>이게 왜 생겼나.</b> Anthropic SDK 의 {@code outputConfig(Class)} 가 하던 일이다. Ollama 의
 * {@code /api/chat} 은 {@code format} 필드에 스키마를 <b>완성된 JSON 으로</b> 받으므로, SDK 를
 * 걷어낸 뒤에는 파생을 우리가 해야 한다. 손으로 적어 두는 선택지도 있었지만 그러면 응답 record 를
 * 고칠 때 스키마가 조용히 뒤처진다 — 형태를 강제하겠다는 스키마가 정작 형태와 어긋나는 상태가
 * 된다. {@code JsonSchemasTest} 가 파생 결과를 고정한다.
 *
 * <p><b>{@code @JsonPropertyDescription} 이 그대로 실린다.</b> 그 주석들은 장식이 아니라 프롬프트의
 * 일부라는 규약이 이미 있고 ({@code JdParseResponse} 참고), 여기서 {@code description} 으로 옮겨야
 * 그 규약이 성립한다.
 *
 * <h2>널 허용 규칙</h2>
 *
 * <b>Java 타입이 그대로 규칙이다.</b> 참조 타입은 {@code ["string","null"]} 처럼 널을 허용하고,
 * 원시 타입({@code int}, {@code boolean})은 허용하지 않는다. 프롬프트가 "공고에 없으면 null" 이라고
 * 적어 둔 필드들이 전부 참조 타입이라 이 규칙 하나로 맞아떨어진다.
 *
 * <p><b>열거형만 예외로 널을 허용하지 않는다.</b> {@code enum} 목록에 {@code null} 을 끼우면
 * Ollama 가 스키마를 GBNF 문법으로 바꿀 때 표현이 지저분해지고, 무엇보다 이 제품의 열거형
 * (요구사항 종류·질문 분류)은 "모름"이 의미를 갖지 않는다. 값이 이상하면 서버측 재검증이 잡는다.
 *
 * <p>모든 필드는 {@code required} 에 들어가고 {@code additionalProperties} 는 {@code false} 다 —
 * 키가 있다는 것과 값이 널이 아니라는 것은 다른 이야기고, 여기서 강제하는 것은 앞쪽이다.
 *
 * <h2>배열 상한</h2>
 *
 * <b>{@link MaxItems} 를 붙인 배열은 {@code maxItems} 를 싣는다.</b> 응답 record 들에 "개수 제약은
 * 구조화 출력이 지원하지 않는다"고 적혀 있던 것은 <b>Anthropic 시절의 사실이고 Ollama 에는 맞지
 * 않는다</b> — 저쪽은 스키마를 GBNF 로 바꾸면서 개수 제약까지 문법에 반영한다. 상한 없는 배열이
 * 실제로 폭주를 일으켰으므로({@link MaxItems} 주석 참고) 이제 배열에는 상한을 건다.
 */
public final class JsonSchemas {

	/** 스키마는 클래스당 한 번만 만들면 된다. 호출마다 리플렉션을 다시 돌 이유가 없다. */
	private static final Map<Class<?>, Map<String, Object>> CACHE = new ConcurrentHashMap<>();

	private JsonSchemas() {
	}

	/**
	 * 최상위 스키마. 루트는 널을 허용하지 않는다 — 응답 전체가 {@code null} 인 것은 스키마로 막을
	 * 일이 아니라 호출 실패다.
	 *
	 * @param type record 여야 한다
	 */
	public static Map<String, Object> of(Class<?> type) {
		return CACHE.computeIfAbsent(type, key -> objectSchema(key, false));
	}

	private static Map<String, Object> objectSchema(Class<?> type, boolean nullable) {
		if (!type.isRecord()) {
			throw new IllegalArgumentException("record 만 스키마로 바꿀 수 있다: " + type.getName());
		}

		Map<String, Object> properties = new LinkedHashMap<>();
		List<String> required = new ArrayList<>();

		for (RecordComponent component : type.getRecordComponents()) {
			Map<String, Object> property = schemaFor(component.getGenericType());
			applyMaxItems(component, property);
			String description = descriptionOf(component);
			if (description != null) {
				property.put("description", description);
			}
			properties.put(component.getName(), property);
			required.add(component.getName());
		}

		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", nullable ? List.of("object", "null") : "object");
		schema.put("properties", properties);
		schema.put("required", required);
		schema.put("additionalProperties", false);
		return schema;
	}

	/**
	 * {@code @JsonPropertyDescription} 을 찾는다.
	 *
	 * <p><b>세 군데를 본다.</b> 이 애너테이션의 {@code @Target} 에는 {@code RECORD_COMPONENT} 가
	 * 없을 수 있고, 그 경우 컴파일러가 필드·접근자·생성자 파라미터 쪽으로만 붙인다. record 헤더에
	 * 적었는데 {@link RecordComponent#getAnnotation} 이 널을 돌려주는 상황이 그래서 생긴다 —
	 * 설명이 통째로 빠진 스키마는 프롬프트가 절반만 나간 것과 같은데, 조용히 그렇게 된다.
	 */
	private static String descriptionOf(RecordComponent component) {
		JsonPropertyDescription direct = component.getAnnotation(JsonPropertyDescription.class);
		if (direct != null) {
			return direct.value();
		}
		JsonPropertyDescription onAccessor = component.getAccessor()
			.getAnnotation(JsonPropertyDescription.class);
		if (onAccessor != null) {
			return onAccessor.value();
		}
		try {
			JsonPropertyDescription onField = component.getDeclaringRecord()
				.getDeclaredField(component.getName())
				.getAnnotation(JsonPropertyDescription.class);
			return onField == null ? null : onField.value();
		}
		catch (NoSuchFieldException ex) {
			return null;
		}
	}

	/**
	 * {@link MaxItems} 를 {@code maxItems} 로 옮긴다.
	 *
	 * <p><b>{@link #descriptionOf} 와 달리 한 군데만 본다.</b> 이 애너테이션은 우리 것이라
	 * {@code @Target} 에 {@code RECORD_COMPONENT} 를 직접 넣어 뒀고, 그래서 리플렉션 경로가 갈리는
	 * 문제가 애초에 생기지 않는다.
	 *
	 * <p><b>배열이 아닌 필드에 붙으면 던진다.</b> 조용히 무시하면 "상한을 걸어 뒀다"고 믿는 채로
	 * 상한 없는 스키마가 나간다 — 이 애너테이션이 막으려는 상황이 정확히 그것이다.
	 */
	private static void applyMaxItems(RecordComponent component, Map<String, Object> property) {
		MaxItems bound = component.getAnnotation(MaxItems.class);
		if (bound == null) {
			return;
		}
		if (!isArray(property)) {
			throw new IllegalArgumentException("@MaxItems 는 List 필드에만 붙일 수 있다: %s.%s"
				.formatted(component.getDeclaringRecord().getSimpleName(), component.getName()));
		}
		if (bound.value() < 1) {
			throw new IllegalArgumentException("@MaxItems 는 1 이상이어야 한다: %s.%s = %d"
				.formatted(component.getDeclaringRecord().getSimpleName(), component.getName(),
						bound.value()));
		}
		property.put("maxItems", bound.value());
	}

	/** 널 허용 배열은 {@code type} 이 목록({@code ["array","null"]})이라 문자열 비교로는 안 걸린다. */
	private static boolean isArray(Map<String, Object> property) {
		Object type = property.get("type");
		return type instanceof List<?> types ? types.contains("array") : "array".equals(type);
	}

	private static Map<String, Object> schemaFor(Type type) {
		if (type instanceof ParameterizedType parameterized) {
			if (parameterized.getRawType() != List.class) {
				throw new IllegalArgumentException("List 외의 제네릭 타입은 지원하지 않는다: " + type);
			}
			Map<String, Object> schema = new LinkedHashMap<>();
			schema.put("type", List.of("array", "null"));
			schema.put("items", schemaFor(parameterized.getActualTypeArguments()[0]));
			return schema;
		}

		if (!(type instanceof Class<?> raw)) {
			throw new IllegalArgumentException("지원하지 않는 타입: " + type);
		}

		if (raw.isEnum()) {
			return enumSchema(raw);
		}
		if (raw.isRecord()) {
			return objectSchema(raw, true);
		}
		if (raw == String.class) {
			return nullable("string");
		}
		if (raw == int.class || raw == long.class || raw == short.class) {
			return strict("integer");
		}
		if (raw == Integer.class || raw == Long.class || raw == Short.class) {
			return nullable("integer");
		}
		if (raw == boolean.class) {
			return strict("boolean");
		}
		if (raw == Boolean.class) {
			return nullable("boolean");
		}
		if (raw == double.class || raw == float.class) {
			return strict("number");
		}
		if (raw == Double.class || raw == Float.class) {
			return nullable("number");
		}
		throw new IllegalArgumentException("지원하지 않는 타입: " + raw.getName());
	}

	private static Map<String, Object> enumSchema(Class<?> type) {
		List<String> values = new ArrayList<>();
		for (Object constant : type.getEnumConstants()) {
			values.add(((Enum<?>) constant).name());
		}
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "string");
		schema.put("enum", values);
		return schema;
	}

	private static Map<String, Object> strict(String type) {
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", type);
		return schema;
	}

	private static Map<String, Object> nullable(String type) {
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", List.of(type, "null"));
		return schema;
	}
}
