package com.jobit.jd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdTextNormalizerTest {

	@Test
	@DisplayName("줄바꿈 형식이 달라도 같은 해시가 나온다")
	void lineEndingsDoNotAffectHash() {
		String crlf = "백엔드 개발자\r\nSpring Boot 3년 이상";
		String lf = "백엔드 개발자\nSpring Boot 3년 이상";

		assertThat(JdTextNormalizer.contentHash(crlf))
			.isEqualTo(JdTextNormalizer.contentHash(lf));
	}

	@Test
	@DisplayName("앞뒤 공백과 연속 공백은 캐시 키에 영향을 주지 않는다")
	void whitespaceIsCollapsed() {
		String messy = "  백엔드   개발자 \n\n\n  Spring  Boot 3년 이상  \n ";
		String clean = "백엔드 개발자\nSpring Boot 3년 이상";

		assertThat(JdTextNormalizer.normalize(messy)).isEqualTo(clean);
		assertThat(JdTextNormalizer.contentHash(messy))
			.isEqualTo(JdTextNormalizer.contentHash(clean));
	}

	@Test
	@DisplayName("전각 문자는 NFKC로 접힌다 — 채용 사이트 복사본에 흔히 섞인다")
	void fullWidthCharactersAreFolded() {
		assertThat(JdTextNormalizer.normalize("Ｓｐｒｉｎｇ　Ｂｏｏｔ")).isEqualTo("Spring Boot");
	}

	@Test
	@DisplayName("대소문자는 보존한다 — 회사명·기술명 표기가 뭉개지면 파싱 품질이 떨어진다")
	void caseIsPreserved() {
		assertThat(JdTextNormalizer.normalize("Spring Boot")).isEqualTo("Spring Boot");
		assertThat(JdTextNormalizer.contentHash("Spring Boot"))
			.isNotEqualTo(JdTextNormalizer.contentHash("spring boot"));
	}

	@Test
	@DisplayName("내용이 다르면 해시가 다르다")
	void differentContentDiffersInHash() {
		assertThat(JdTextNormalizer.contentHash("백엔드 개발자"))
			.isNotEqualTo(JdTextNormalizer.contentHash("프론트엔드 개발자"));
	}

	@Test
	@DisplayName("해시는 SHA-256 hex 64자")
	void hashIsSha256Hex() {
		assertThat(JdTextNormalizer.contentHash("아무 공고")).hasSize(64).matches("[0-9a-f]{64}");
	}

	@Test
	void nullIsRejected() {
		assertThatThrownBy(() -> JdTextNormalizer.normalize(null))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
