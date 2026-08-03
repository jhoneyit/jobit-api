package com.jobit.llm;

import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StructuredMessageCreateParams;

/**
 * 구조화 출력과 effort를 함께 거는 헬퍼.
 *
 * <p><b>이게 왜 필요한가.</b> SDK에서 {@code outputConfig(Class)}는 클래스로부터 JSON Schema를
 * 파생시켜 {@code OutputConfig}를 <b>통째로 새로 만든다.</b> 그래서 앞서 걸어 둔
 * {@code outputConfig(OutputConfig.builder().effort(...))}가 조용히 지워진다 — 에러도 경고도 없다.
 * 그 상태로 나가면 파싱이 기본 effort(high)로 돌아 비용이 몇 배가 된다.
 *
 * <p>순서를 뒤집어도 안 된다. 나중에 부른 {@code outputConfig(OutputConfig)}가 이번에는 스키마를
 * 지운다. 그래서 <b>파생된 스키마를 꺼내 effort와 함께 다시 조립</b>하는 수밖에 없다.
 *
 * <p>동작은 {@code JdParseParamsTest}가 고정한다. SDK를 올릴 때 그 테스트가 깨지면 이 헬퍼부터 본다.
 */
public final class StructuredOutput {

	private StructuredOutput() {
	}

	/**
	 * @param builder {@code outputConfig(Class)}까지 세팅된 빌더
	 * @param effort  적용할 effort
	 */
	public static <T> StructuredMessageCreateParams<T> withEffort(
			StructuredMessageCreateParams.Builder<T> builder, OutputConfig.Effort effort) {

		OutputConfig derived = builder.build()
			.rawParams()
			.outputConfig()
			.orElseThrow(() -> new IllegalStateException(
					"outputConfig(Class)를 먼저 호출해야 한다 — 파생된 스키마가 없다"));

		JsonOutputFormat format = derived.format()
			.orElseThrow(() -> new IllegalStateException(
					"구조화 출력 스키마가 없다 — outputConfig(Class) 호출을 확인하라"));

		return builder.outputConfig(OutputConfig.builder().format(format).effort(effort).build())
			.build();
	}
}
