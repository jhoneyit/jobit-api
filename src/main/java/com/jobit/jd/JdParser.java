package com.jobit.jd;

import java.util.List;

/**
 * JD 본문에서 구조화된 요구사항을 뽑아내는 포트 (스펙 §4.1 3단계).
 *
 * <p>구현체는 LLM을 호출한다. 인터페이스로 둔 이유는 제공자 추상화가 아니라 <b>구현이 아직 없기
 * 때문</b>이다 — 캐시·이력 로직을 LLM 없이 먼저 완성하고 테스트하기 위한 이음매다.
 * 구현이 하나로 확정되면 이 인터페이스는 없애도 된다.
 *
 * <p>구조화 출력(JSON schema)은 필수이며, 서버에서 재검증하고 실패 시 재시도한다 (스펙 §6).
 */
public interface JdParser {

	ParsedJd parse(String rawText);

	/**
	 * @param parsedJson 스택·연차·도메인 등. {@code job_posting.parsed}에 그대로 저장된다.
	 */
	record ParsedJd(String company, String title, String parsedJson,
			List<ParsedRequirement> requirements) {
	}

	record ParsedRequirement(String text, Requirement.Kind kind, List<String> keywords) {
	}
}
