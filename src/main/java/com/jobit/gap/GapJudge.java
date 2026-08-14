package com.jobit.gap;

import java.util.List;
import java.util.UUID;

/**
 * 요구사항 하나를 이력서 문장 후보들과 대조해 판정한다 (스펙 §4.3 2단계).
 *
 * <p>인터페이스로 두는 이유는 {@code JdParser}·{@code AnswerScorer} 와 같다 —
 * {@code ollama.base-url} 이 없으면 {@link GapJudgeFallbackConfig} 의 폴백이 자리를 지켜
 * 앱은 뜨고, 판정을 호출하는 순간 명확한 예외가 난다.
 */
public interface GapJudge {

	Verdict judge(Request request);

	/**
	 * @param candidates 임베딩이 추린 상위 후보 (스펙 §4.3 1단계). 유사도 순이지만
	 *                   판정에서는 순서가 근거가 아니다 — 프롬프트가 그렇게 말한다
	 */
	record Request(String requirementText, List<Candidate> candidates) {
	}

	record Candidate(UUID bulletId, String text) {
	}

	/**
	 * @param evidenceBulletId 근거가 된 문장. {@code MISSING} 이면 null 이다 — "근거 없음"을
	 *                         그대로 노출하는 것이 스펙 §4.5 의 요구다
	 */
	record Verdict(GapItem.Status status, UUID evidenceBulletId, String rationale) {
	}
}
