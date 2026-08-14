package com.jobit.gap;

import java.util.List;

/**
 * 모델이 돌려준 판정을 화면이 믿을 수 있는 형태로 다듬는다.
 *
 * <p>{@code AnswerScoreNormalizer} 와 같은 자리다 — 실제 호출 없이 경계값을 전부 태울 수 있어야
 * 하고, 여기서 나는 실수는 전부 조용하다.
 *
 * <p><b>핵심 규칙: 근거를 대지 못한 충족 판정은 충족이 아니다.</b> MET/WEAK 인데 인덱스가 없거나
 * 범위 밖이면 MISSING 으로 내린다. 반대 방향(근거 없는 항목을 MET 으로 올리는 것)은 이력서에 없는
 * 내용을 지어내는 것과 같아서 이 제품이 가장 금지하는 실수다 — 틀릴 거라면 <b>사용자가 준비를 더
 * 하게 만드는 쪽</b>으로 틀린다 (채점이 확신 없는 항목을 covered 에 넣지 않는 것과 같은 방향).
 */
final class GapVerdictNormalizer {

	/** 판정을 내리지 못했을 때 사용자에게 보여줄 문구. 내부 사정(인덱스 오류)을 담지 않는다. */
	static final String UNVERIFIABLE_RATIONALE = "이력서에서 이 요구사항의 근거를 확인하지 못했습니다.";

	private GapVerdictNormalizer() {
	}

	/**
	 * @param candidates 판정에 넘긴 후보. {@code evidenceIndex} 의 유효 범위이자 인덱스 → 문장 ID
	 *                   변환표다
	 */
	static GapJudge.Verdict normalize(GapJudgeResponse response,
			List<GapJudge.Candidate> candidates) {

		// 스키마의 enum 은 널을 허용하지 않지만, 방어는 역직렬화 결과 기준으로 한다.
		GapItem.Status status = response == null || response.status() == null
				? GapItem.Status.MISSING : response.status();

		Integer index = response == null ? null : response.evidenceIndex();
		boolean validIndex = index != null && index >= 0 && index < candidates.size();

		String rationale = normalizeRationale(response == null ? null : response.rationale());

		if (status == GapItem.Status.MISSING) {
			// MISSING 에 근거가 붙어 오면 근거를 버린다. "근거 없음"에 근거가 달려 있으면
			// 화면의 표가 자기모순이 된다.
			return new GapJudge.Verdict(GapItem.Status.MISSING, null, rationale);
		}

		if (!validIndex) {
			// 근거 없는 MET/WEAK. rationale 도 함께 버린다 — 그 문구는 존재하지 않는 근거를
			// 설명하고 있을 것이므로, 남기면 "근거 없음" 판정과 서로 다른 말을 한다.
			return new GapJudge.Verdict(GapItem.Status.MISSING, null, UNVERIFIABLE_RATIONALE);
		}

		return new GapJudge.Verdict(status, candidates.get(index).bulletId(), rationale);
	}

	/**
	 * 근거 없는 MET/WEAK 인가 — 재시도할 가치가 있는 유일한 실패다.
	 *
	 * <p>{@link #normalize} 는 이 경우를 MISSING 으로 내리지만, 그 강등은 <b>모델이 근거를 봤는데
	 * 인덱스만 빠뜨렸을 때</b> 사용자에게 거짓 MISSING 을 보여준다. 그래서 호출부가 강등 전에 한 번
	 * 다시 물을 수 있게 조건을 꺼내 둔다. 판정 자체(MISSING)나 형식이 멀쩡한 응답은 재시도 대상이
	 * 아니다 — 그건 결과가 마음에 안 드는 것이지 실패가 아니다.
	 */
	static boolean unverifiable(GapJudgeResponse response, int candidateCount) {
		if (response == null || response.status() == null
				|| response.status() == GapItem.Status.MISSING) {
			return false;
		}
		Integer index = response.evidenceIndex();
		return index == null || index < 0 || index >= candidateCount;
	}

	private static String normalizeRationale(String rationale) {
		return (rationale == null || rationale.isBlank()) ? UNVERIFIABLE_RATIONALE
				: rationale.strip();
	}
}
