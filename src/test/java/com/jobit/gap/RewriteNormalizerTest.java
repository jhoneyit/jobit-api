package com.jobit.gap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 리라이트 재검증의 경계값.
 *
 * <p><b>숫자 검증이 이 클래스의 존재 이유다</b> — "지어내지 않는다"는 프롬프트 지시를 모델이
 * 지켰는지는 서버만 확인할 수 있고, 지어낸 숫자 하나가 이력서의 거짓말이 된다.
 */
class RewriteNormalizerTest {

	private static final String ORIGINAL = "정산 배치를 운영하며 배치 시간을 40% 단축했습니다.";

	private static final String REQUIREMENT = "대용량 트래픽 처리 경험 3년 이상";

	private static RewriteResponse response(String suggested) {
		return new RewriteResponse(suggested, "이유");
	}

	@Test
	@DisplayName("원문의 숫자를 그대로 쓰면 통과한다")
	void allowsNumbersFromOriginal() {
		assertThat(RewriteNormalizer.problem(
				response("정산 배치 파이프라인을 운영하며 배치 시간을 40% 단축했습니다"), ORIGINAL, REQUIREMENT))
			.isNull();
	}

	@Test
	@DisplayName("요구사항의 숫자도 허용한다 — '경력 3년 이상'을 문장이 받아 쓸 수 있다")
	void allowsNumbersFromRequirement() {
		assertThat(RewriteNormalizer.problem(
				response("3년간 정산 배치를 운영하며 배치 시간을 40% 단축했습니다"), ORIGINAL, REQUIREMENT))
			.isNull();
	}

	@Test
	@DisplayName("출처 없는 숫자는 거부한다 — 지어낸 숫자 하나가 이력서의 거짓말이 된다")
	void rejectsFabricatedNumbers() {
		assertThat(RewriteNormalizer.problem(
				response("일일 300만 건 규모의 정산 배치를 운영했습니다"), ORIGINAL, REQUIREMENT))
			.contains("300");
	}

	@Test
	@DisplayName("자리 표시 안의 숫자는 허용한다 — 사용자가 채울 자리다")
	void allowsNumbersInsidePlaceholders() {
		assertThat(RewriteNormalizer.problem(
				response("[일일 처리 건수, 예: 100만]건 규모의 정산 배치를 운영했습니다"), ORIGINAL,
				REQUIREMENT))
			.isNull();
	}

	@Test
	@DisplayName("원문과 같은 문장은 거부한다 — 고친 것이 없다")
	void rejectsUnchangedSuggestion() {
		assertThat(RewriteNormalizer.problem(response("  " + ORIGINAL + "  "), ORIGINAL,
				REQUIREMENT))
			.isNotNull();
	}

	@Test
	@DisplayName("빈 suggested·reason·응답 없음은 전부 불합격이다")
	void rejectsBlankParts() {
		assertThat(RewriteNormalizer.problem(response("   "), ORIGINAL, REQUIREMENT)).isNotNull();
		assertThat(RewriteNormalizer.problem(new RewriteResponse("고친 문장", " "), ORIGINAL,
				REQUIREMENT)).isNotNull();
		assertThat(RewriteNormalizer.problem(null, ORIGINAL, REQUIREMENT)).isNotNull();
	}

	@Test
	@DisplayName("fabricatedNumber 는 어긋난 숫자를 그대로 돌려준다 — 로그가 무엇이 샜는지 말해야 한다")
	void reportsWhichNumberLeaked() {
		assertThat(RewriteNormalizer.fabricatedNumber("처리량을 87% 개선", ORIGINAL, REQUIREMENT))
			.isEqualTo("87");
		assertThat(RewriteNormalizer.fabricatedNumber("배치 시간을 40% 단축", ORIGINAL, REQUIREMENT))
			.isNull();
	}
}
