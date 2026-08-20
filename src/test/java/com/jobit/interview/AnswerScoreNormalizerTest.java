package com.jobit.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 채점 결과 재검증 (docs/interview-practice-design.md §5).
 *
 * <p>여기가 이 기능에서 가장 틀리기 쉬운 곳이다 — 모델이 준 인덱스를 그대로 믿으면 화면이
 * 뼈대와 짝을 맞추다 터지고, DB CHECK 제약에도 걸린다. 실제 호출 없이 경계값을 전부 태운다.
 */
class AnswerScoreNormalizerTest {

	@Test
	@DisplayName("missed 는 covered 의 여집합이다 — 둘을 합치면 항상 전체이고 겹치지 않는다")
	void derivesMissedAsComplement() {
		AnswerScorer.Score score = AnswerScoreNormalizer.normalize(72, List.of(0, 2), 4, "괜찮습니다.");

		assertThat(score.covered()).containsExactly(0, 2);
		assertThat(score.missed()).containsExactly(1, 3);
	}

	@Test
	@DisplayName("범위 밖 인덱스는 버린다 — 뼈대가 3개인데 7을 지어내도 나머지는 살린다")
	void dropsOutOfRangeIndexes() {
		AnswerScorer.Score score = AnswerScoreNormalizer.normalize(50, List.of(0, 7, -1, 2), 3,
				"일부만 짚었습니다.");

		assertThat(score.covered()).containsExactly(0, 2);
		assertThat(score.missed()).containsExactly(1);
	}

	@Test
	@DisplayName("중복 인덱스는 한 번만 센다")
	void dedupesIndexes() {
		AnswerScorer.Score score = AnswerScoreNormalizer.normalize(80, List.of(1, 1, 1), 2, "좋습니다.");

		assertThat(score.covered()).containsExactly(1);
		assertThat(score.missed()).containsExactly(0);
	}

	@Test
	@DisplayName("인덱스를 오름차순으로 돌려준다 — 화면이 매번 정렬하지 않게")
	void sortsIndexes() {
		AnswerScorer.Score score = AnswerScoreNormalizer.normalize(90, List.of(3, 0, 2), 4, "좋습니다.");

		assertThat(score.covered()).containsExactly(0, 2, 3);
	}

	@Test
	@DisplayName("covered 안의 null 을 견딘다 — 구조화 출력이라도 원소 null 은 막지 못한다")
	void toleratesNullElements() {
		AnswerScorer.Score score = AnswerScoreNormalizer.normalize(30,
				Arrays.asList(0, null, 2), 3, "일부만 짚었습니다.");

		assertThat(score.covered()).containsExactly(0, 2);
	}

	@Test
	@DisplayName("covered 가 null 이면 전부 놓친 것으로 본다")
	void treatsNullCoveredAsNothing() {
		AnswerScorer.Score score = AnswerScoreNormalizer.normalize(0, null, 3, "답변이 빗나갔습니다.");

		assertThat(score.covered()).isEmpty();
		assertThat(score.missed()).containsExactly(0, 1, 2);
	}

	@Test
	@DisplayName("점수는 0~100 으로 자른다 — DB CHECK 제약에 닿기 전에 맞춘다")
	void clampsScore() {
		// covered 를 꽉 채워 상한(아래 테스트)과 분리하고 클램프만 본다.
		assertThat(AnswerScoreNormalizer.normalize(120, List.of(0, 1), 2, "x").score())
			.isEqualTo(100);
		assertThat(AnswerScoreNormalizer.normalize(-5, List.of(0, 1), 2, "x").score()).isZero();
		assertThat(AnswerScoreNormalizer.normalize(63, List.of(0, 1), 2, "x").score())
			.isEqualTo(63);
	}

	@Test
	@DisplayName("점수는 검증된 covered 비율을 넘지 못한다 — 근거 없이 점수만 높은 응답이 여기서 잘린다")
	void capsScoreByCoveredRatio() {
		// 뼈대 4개 중 1개만 짚고 100점 — 주입의 전형이다. 상한 25.
		assertThat(AnswerScoreNormalizer.normalize(100, List.of(0), 4, "x").score()).isEqualTo(25);
		// 하나도 못 짚으면 점수도 0 이다.
		assertThat(AnswerScoreNormalizer.normalize(100, List.of(), 4, "x").score()).isZero();
		// 상한 아래 점수는 그대로다.
		assertThat(AnswerScoreNormalizer.normalize(40, List.of(0, 1), 4, "x").score())
			.isEqualTo(40);
	}

	@Test
	@DisplayName("인용 검증 — 답변에 없는 인용이 달린 항목은 버린다 (주입 방어 2층)")
	void dropsCoveredWithFabricatedQuotes() {
		String transcript = "격리 수준은 네 가지가 있고, MySQL 기본은 리피터블 리드입니다.";

		AnswerScorer.Score score = AnswerScoreNormalizer.normalize(100, List.of(
				new AnswerScoreResponse.Covered(0, "격리 수준은 네 가지"),      // 원문에 있음
				new AnswerScoreResponse.Covered(1, "팬텀 리드를 락으로 해결"),  // 지어낸 인용
				new AnswerScoreResponse.Covered(2, "네 가지")),                // 너무 짧음
				4, "x", transcript);

		assertThat(score.covered()).containsExactly(0);
		assertThat(score.score()).isEqualTo(25); // 검증된 1/4 이 상한이다
	}

	@Test
	@DisplayName("인용의 공백 차이는 눈감아 준다 — 줄바꿈을 다듬어 옮긴 것까지 버리면 과하다")
	void quoteMatchIgnoresWhitespace() {
		String transcript = "MySQL 기본은\n리피터블 리드라서\n그대로 썼습니다";

		AnswerScorer.Score score = AnswerScoreNormalizer.normalize(50, List.of(
				new AnswerScoreResponse.Covered(0, "MySQL 기본은 리피터블 리드라서")), 2, "x",
				transcript);

		assertThat(score.covered()).containsExactly(0);
	}

	@Test
	@DisplayName("빈 피드백은 null 로 눕힌다 — 화면이 빈 문자열과 없음을 따로 다루지 않게")
	void normalizesBlankFeedback() {
		assertThat(AnswerScoreNormalizer.normalize(50, List.of(), 2, "   ").feedback()).isNull();
		assertThat(AnswerScoreNormalizer.normalize(50, List.of(), 2, null).feedback()).isNull();
		assertThat(AnswerScoreNormalizer.normalize(50, List.of(), 2, "  좋습니다.  ").feedback())
			.isEqualTo("좋습니다.");
	}

	@Test
	@DisplayName("전부 짚었으면 missed 는 비어 있다")
	void allCoveredLeavesNothingMissed() {
		AnswerScorer.Score score = AnswerScoreNormalizer.normalize(100, List.of(0, 1, 2), 3, "완벽합니다.");

		assertThat(score.missed()).isEmpty();
	}

	@Test
	@DisplayName("답하지 않은 문항은 0점이고 전부 놓친 것이다")
	void unansweredScoresZero() {
		AnswerScorer.Score score = AnswerScoreNormalizer.unanswered(3);

		assertThat(score.score()).isZero();
		assertThat(score.covered()).isEmpty();
		assertThat(score.missed()).containsExactly(0, 1, 2);
		assertThat(score.feedback()).isNotBlank();
	}

	@Test
	@DisplayName("뼈대가 없으면 채점하지 않는다 — 기준이 없는데 점수를 매기면 안 된다")
	void rejectsEmptyOutline() {
		assertThatThrownBy(() -> AnswerScoreNormalizer.normalize(50, List.of(), 0, "x"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AnswerScoreNormalizer.unanswered(0))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("돌려준 목록은 불변이다 — 호출부가 실수로 고쳐도 원본이 흔들리지 않는다")
	void returnsImmutableLists() {
		AnswerScorer.Score score = AnswerScoreNormalizer.normalize(50, List.of(0), 2, "x");

		assertThatThrownBy(() -> score.covered().add(1))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> score.missed().add(0))
			.isInstanceOf(UnsupportedOperationException.class);
	}
}
