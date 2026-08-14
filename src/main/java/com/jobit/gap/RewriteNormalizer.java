package com.jobit.gap;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 리라이트 응답의 서버측 재검증.
 *
 * <p>{@code GapVerdictNormalizer} 와 같은 자리지만 성격이 다르다 — 그쪽은 강등으로 다듬고,
 * 여기는 <b>불합격 사유를 돌려줘 재시도를 유도한다.</b> 수정안은 강등해서 살릴 수 있는 값이
 * 아니다: 지어낸 숫자가 섞인 문장을 "덜 지어낸" 문장으로 고칠 방법이 서버에는 없다.
 *
 * <p><b>숫자 검증이 핵심이다.</b> "지어내지 않는다"는 프롬프트에도 있지만, 그 지시를 모델이
 * 따랐는지는 서버만 확인할 수 있다 — 수정안의 모든 숫자는 원문이나 요구사항에 이미 있었거나
 * 대괄호 자리 표시 안에 있어야 한다. 이력서에 들어갈 문장이라, 지어낸 숫자 하나가 그대로
 * 거짓말이 된다.
 */
final class RewriteNormalizer {

	private static final Pattern PLACEHOLDER = Pattern.compile("\\[[^\\]]*]");

	private static final Pattern DIGIT_RUN = Pattern.compile("\\d+");

	private RewriteNormalizer() {
	}

	/**
	 * 불합격 사유. 통과하면 null 이다.
	 *
	 * <p>사유는 <b>로그·재시도 판단용</b>이다. 사용자 문구가 아니므로 여기서 다듬지 않는다 —
	 * 이력서 문장을 인용하지 않는 것만 지킨다 (숫자는 그 자체로 개인 식별 정보가 아니다).
	 */
	static String problem(RewriteResponse response, String original, String requirementText) {
		if (response == null) {
			return "응답이 없다";
		}
		if (response.suggested() == null || response.suggested().isBlank()) {
			return "suggested 가 비어 있다";
		}
		if (response.reason() == null || response.reason().isBlank()) {
			return "reason 이 비어 있다";
		}
		if (response.suggested().strip().equals(original.strip())) {
			return "원문과 같다 — 고친 것이 없다";
		}
		String fabricated = fabricatedNumber(response.suggested(), original, requirementText);
		if (fabricated != null) {
			return "원문에 없는 숫자를 지어냈다: " + fabricated;
		}
		return null;
	}

	/**
	 * 수정안에서 <b>출처 없는 숫자</b>를 찾는다. 없으면 null.
	 *
	 * <p>허용되는 출처는 셋이다: 원문, 요구사항 텍스트(예: "경력 3년 이상"의 3), 대괄호 자리 표시
	 * 안({@code [개선 전후 수치]%} 처럼 이름에 숫자가 들어갈 일은 드물지만, 자리 표시는 사용자가
	 * 채울 자리라 통째로 제외한다).
	 *
	 * <p>부분 문자열 검사라 원문 숫자의 조각과 우연히 겹치는 지어낸 숫자는 통과한다
	 * (원문에 "2021"이 있으면 지어낸 "20"도 통과). 완벽하지 않지만, 그 경우 새는 숫자가 원문에
	 * 이미 있던 숫자의 조각이라 거짓말의 크기가 제한된다 — 아예 새로운 수치("300만 건")는 잡힌다.
	 */
	static String fabricatedNumber(String suggested, String original, String requirementText) {
		String outsidePlaceholders = PLACEHOLDER.matcher(suggested).replaceAll(" ");
		Matcher runs = DIGIT_RUN.matcher(outsidePlaceholders);
		while (runs.find()) {
			String run = runs.group();
			if (!original.contains(run) && !requirementText.contains(run)) {
				return run;
			}
		}
		return null;
	}
}
