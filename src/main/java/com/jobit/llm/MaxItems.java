package com.jobit.llm;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 배열 필드의 원소 개수 상한. {@link JsonSchemas} 가 {@code maxItems} 로 실어 보낸다.
 *
 * <p><b>이건 문서가 아니라 문법이다.</b> Ollama 는 {@code format} 스키마를 GBNF 로 바꿔 토큰 단위로
 * 제약한다. 상한이 없는 배열은 "원소를 하나 더 붙인다"가 언제나 합법이라, 모델이 반복에 빠져도
 * <b>문법이 종료를 강제하지 못하고</b> 출력 상한까지 간다.
 *
 * <p><b>2026-08-13 JD 파싱이 실제로 그렇게 됐다.</b> {@code parsed.keywords} 에 같은 문구를 100번
 * 넣으며 4,000토큰을 태웠고, 정작 {@code requirements} 는 시작도 못 한 채 잘렸다. 5분을 쓰고 재시도로
 * 살아나는 실패라 결과만 보면 멀쩡해 보인다 — 로그의 지연 시간에만 남는다.
 *
 * <p><b>개수 규칙을 설명에만 적어 두면 지켜지지 않는다.</b> 그 필드의
 * {@code @JsonPropertyDescription} 에는 이미 "키워드 5~10개"라고 적혀 있었고, 모델은 351개를 냈다.
 * 같은 규칙을 여기 적으면 문법이 강제한다. <b>글로 적는 규칙과 문법으로 거는 규칙을 함께 둔다</b> —
 * 설명은 "왜 그 개수인지"를, 이 애너테이션은 "넘을 수 없음"을 맡는다.
 *
 * <p><b>상한에 닿으면 조용히 닫힌다.</b> 넘치는 원소는 오류가 아니라 그냥 생성되지 않는다. 그래서
 * 실제로 나올 법한 최대치보다 넉넉하게 잡는다 — 여기서 아끼면 진짜 내용이 잘려 나간다. 반대로
 * 너무 크게 잡으면 폭주를 늦게 끊을 뿐이라, 기준은 "정상 결과가 절대 닿지 않을 값 중 가장 작은 것"이다.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface MaxItems {

	int value();
}
