package com.jobit.member;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResetTokensTest {

	@Test
	@DisplayName("매번 다른 토큰이 나온다")
	void tokensAreUnique() {
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 1000; i++) {
			seen.add(ResetTokens.generate());
		}
		assertThat(seen).hasSize(1000);
	}

	@Test
	@DisplayName("URL에 그대로 실을 수 있다 — base64url, 패딩 없음")
	void tokenIsUrlSafe() {
		assertThat(ResetTokens.generate()).matches("[A-Za-z0-9_-]+").doesNotContain("=");
	}

	@Test
	@DisplayName("256비트 난수 — base64로 43자")
	void tokenHasEnoughEntropy() {
		assertThat(ResetTokens.generate()).hasSize(43);
	}

	@Test
	@DisplayName("해시는 결정적이다 — 조회에 쓰려면 salt가 없어야 한다")
	void hashIsDeterministic() {
		String token = ResetTokens.generate();

		assertThat(ResetTokens.hash(token)).isEqualTo(ResetTokens.hash(token));
	}

	@Test
	@DisplayName("해시에서 원문을 유추할 수 없고, 원문과 다르다")
	void hashDiffersFromToken() {
		String token = ResetTokens.generate();

		assertThat(ResetTokens.hash(token)).isNotEqualTo(token).hasSize(64).matches("[0-9a-f]{64}");
	}

	@Test
	@DisplayName("다른 토큰은 다른 해시")
	void differentTokensDifferentHashes() {
		assertThat(ResetTokens.hash(ResetTokens.generate()))
			.isNotEqualTo(ResetTokens.hash(ResetTokens.generate()));
	}
}
