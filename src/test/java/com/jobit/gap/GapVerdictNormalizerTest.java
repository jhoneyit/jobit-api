package com.jobit.gap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 판정 정규화의 경계값 ({@code AnswerScoreNormalizerTest} 와 같은 자리).
 *
 * <p>핵심 성질: <b>근거를 대지 못한 충족 판정은 충족이 아니다.</b> 틀릴 거라면 사용자가 준비를
 * 더 하게 만드는 쪽(MISSING)으로 틀린다.
 */
class GapVerdictNormalizerTest {

	private static final UUID BULLET_A = UUID.randomUUID();

	private static final UUID BULLET_B = UUID.randomUUID();

	private static final List<GapJudge.Candidate> CANDIDATES = List.of(
			new GapJudge.Candidate(BULLET_A, "문장 A"), new GapJudge.Candidate(BULLET_B, "문장 B"));

	@Test
	@DisplayName("유효한 판정은 인덱스가 문장 ID 로 바뀐다")
	void mapsIndexToBulletId() {
		GapJudge.Verdict verdict = GapVerdictNormalizer.normalize(
				new GapJudgeResponse(GapItem.Status.MET, 1, "근거가 분명하다"), CANDIDATES);

		assertThat(verdict.status()).isEqualTo(GapItem.Status.MET);
		assertThat(verdict.evidenceBulletId()).isEqualTo(BULLET_B);
		assertThat(verdict.rationale()).isEqualTo("근거가 분명하다");
	}

	@Test
	@DisplayName("MISSING 에 근거가 붙어 오면 근거를 버린다 — '근거 없음'에 근거가 달리면 자기모순이다")
	void dropsEvidenceOnMissing() {
		GapJudge.Verdict verdict = GapVerdictNormalizer.normalize(
				new GapJudgeResponse(GapItem.Status.MISSING, 0, "근거가 없다"), CANDIDATES);

		assertThat(verdict.status()).isEqualTo(GapItem.Status.MISSING);
		assertThat(verdict.evidenceBulletId()).isNull();
		assertThat(verdict.rationale()).isEqualTo("근거가 없다");
	}

	@Test
	@DisplayName("근거 없는 MET 은 MISSING 으로 내린다 — 지어낸 충족이 이 제품이 가장 금지하는 실수다")
	void downgradesMetWithoutEvidence() {
		GapJudge.Verdict verdict = GapVerdictNormalizer.normalize(
				new GapJudgeResponse(GapItem.Status.MET, null, "충족한다"), CANDIDATES);

		assertThat(verdict.status()).isEqualTo(GapItem.Status.MISSING);
		assertThat(verdict.evidenceBulletId()).isNull();
		// rationale 도 버린다 — "충족한다"가 MISSING 옆에 남으면 서로 다른 말을 한다.
		assertThat(verdict.rationale()).isEqualTo(GapVerdictNormalizer.UNVERIFIABLE_RATIONALE);
	}

	@Test
	@DisplayName("범위 밖 인덱스도 같은 취급이다 — 모델은 후보가 2개인데 5를 지어내기도 한다")
	void downgradesOutOfRangeIndex() {
		GapJudge.Verdict tooBig = GapVerdictNormalizer.normalize(
				new GapJudgeResponse(GapItem.Status.WEAK, 5, "언급이 있다"), CANDIDATES);
		GapJudge.Verdict negative = GapVerdictNormalizer.normalize(
				new GapJudgeResponse(GapItem.Status.WEAK, -1, "언급이 있다"), CANDIDATES);

		assertThat(tooBig.status()).isEqualTo(GapItem.Status.MISSING);
		assertThat(negative.status()).isEqualTo(GapItem.Status.MISSING);
	}

	@Test
	@DisplayName("빈 rationale 은 고정 문구로 채운다 — 화면의 근거 칸이 비면 버그로 보인다")
	void fillsBlankRationale() {
		GapJudge.Verdict verdict = GapVerdictNormalizer.normalize(
				new GapJudgeResponse(GapItem.Status.MISSING, null, "   "), CANDIDATES);

		assertThat(verdict.rationale()).isEqualTo(GapVerdictNormalizer.UNVERIFIABLE_RATIONALE);
	}

	@Test
	@DisplayName("응답이 통째로 없으면 MISSING 이다")
	void treatsNullResponseAsMissing() {
		GapJudge.Verdict verdict = GapVerdictNormalizer.normalize(null, CANDIDATES);

		assertThat(verdict.status()).isEqualTo(GapItem.Status.MISSING);
		assertThat(verdict.evidenceBulletId()).isNull();
	}

	@Test
	@DisplayName("unverifiable — 근거 없는 MET/WEAK 만 재시도 대상이다")
	void unverifiableOnlyForUnbackedClaims() {
		// 재시도할 것: 근거 없는 충족 판정
		assertThat(GapVerdictNormalizer
			.unverifiable(new GapJudgeResponse(GapItem.Status.MET, null, "r"), 2)).isTrue();
		assertThat(GapVerdictNormalizer
			.unverifiable(new GapJudgeResponse(GapItem.Status.WEAK, 9, "r"), 2)).isTrue();

		// 재시도하지 않을 것: 형식이 멀쩡한 응답과 MISSING
		assertThat(GapVerdictNormalizer
			.unverifiable(new GapJudgeResponse(GapItem.Status.MET, 0, "r"), 2)).isFalse();
		assertThat(GapVerdictNormalizer
			.unverifiable(new GapJudgeResponse(GapItem.Status.MISSING, null, "r"), 2)).isFalse();
		assertThat(GapVerdictNormalizer.unverifiable(null, 2)).isFalse();
	}
}
