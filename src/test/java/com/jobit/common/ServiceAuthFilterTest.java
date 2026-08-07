package com.jobit.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobit.PostgresTestContainer;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/**
 * 필터가 실제로 요청을 막는지 (docs/architecture.md "호출자 인증").
 *
 * <p><b>서명 계산은 {@code ServiceAuthTest} 가 본다.</b> 여기서 확인할 것은 그 판정이
 * <b>요청 경로에 실제로 걸리는가</b> — 특히 컨트롤러가 손대지 않아도 {@code /api/**} 전체가
 * 기본값 "닫힘"인가다. 하나라도 새면 그 경로가 뒷문이 된다.
 */
class ServiceAuthFilterTest {

	private static final String SECRET = "filter-test-secret-that-is-long-enough";

	private static final String OWNER = "user:filter-test";

	@Nested
	@SpringBootTest(properties = "jobit.auth.service-secret=" + SECRET)
	@AutoConfigureMockMvc
	@Import(PostgresTestContainer.class)
	@DisplayName("인증이 켜져 있으면")
	class Enabled {

		@Autowired
		private MockMvc mockMvc;

		@Autowired
		private ServiceAuthConfig config;

		@Test
		@DisplayName("서명이 없으면 401 — 개인 자산 조회가 그대로 열려 있으면 안 된다")
		void rejectsUnsignedRequest() throws Exception {
			mockMvc.perform(get("/api/submissions").header(ServiceAuth.OWNER_HEADER, OWNER))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("인증되지 않은 요청입니다."));
		}

		@Test
		@DisplayName("서명이 있으면 통과한다")
		void acceptsSignedRequest() throws Exception {
			mockMvc
				.perform(get("/api/submissions").header(ServiceAuth.OWNER_HEADER, OWNER)
					.header(ServiceAuth.AUTH_HEADER, sign(OWNER)))
				.andExpect(status().isOk());
		}

		@Test
		@DisplayName("남의 owner_key 로 바꿔치기하면 401 — 사칭이 실제로 막힌다")
		void rejectsSwappedOwnerKey() throws Exception {
			mockMvc
				.perform(get("/api/submissions").header(ServiceAuth.OWNER_HEADER, "user:victim")
					.header(ServiceAuth.AUTH_HEADER, sign(OWNER)))
				.andExpect(status().isUnauthorized());
		}

		@Test
		@DisplayName("owner_key 를 안 쓰는 공개 통계도 서명을 요구한다 — 예외가 곧 뒷문이다")
		void protectsEndpointsWithoutOwnerKey() throws Exception {
			mockMvc.perform(get("/api/stats/stacks")).andExpect(status().isUnauthorized());

			mockMvc.perform(get("/api/stats/stacks").header(ServiceAuth.AUTH_HEADER, sign(null)))
				.andExpect(status().isOk());
		}

		@Test
		@DisplayName("거절 이유를 응답에 담지 않는다 — 무엇을 고쳐야 하는지 알려 주지 않는다")
		void doesNotLeakFailureReason() throws Exception {
			String expired = ServiceAuth.sign(OWNER, Instant.now().minusSeconds(600), SECRET);

			mockMvc
				.perform(get("/api/submissions").header(ServiceAuth.OWNER_HEADER, OWNER)
					.header(ServiceAuth.AUTH_HEADER, expired))
				.andExpect(status().isUnauthorized())
				// 만료든 위조든 같은 문구다.
				.andExpect(jsonPath("$.error").value("인증되지 않은 요청입니다."));
		}

		@Test
		@DisplayName("설정이 켜진 것으로 인식된다")
		void reportsEnabled() {
			assertThat(config.enabled()).isTrue();
		}

		private String sign(String ownerKey) {
			return ServiceAuth.sign(ownerKey, Instant.now().plusSeconds(120), SECRET);
		}
	}

	/**
	 * 비밀키가 없으면 인증을 건너뛴다 — 두 레포를 설정하지 않고도 로컬에서 돌려 보기 위한 것이다.
	 * 운영에서 조용히 열리는 것은 {@code prod} 프로파일 검사가 막는다.
	 */
	@Nested
	@SpringBootTest(properties = "jobit.auth.service-secret=")
	@AutoConfigureMockMvc
	@Import(PostgresTestContainer.class)
	@DisplayName("비밀키가 없으면")
	class Disabled {

		@Autowired
		private MockMvc mockMvc;

		@Autowired
		private ServiceAuthConfig config;

		@Test
		@DisplayName("서명 없이도 통과한다 — 로컬에서 설정 없이 돌아간다")
		void allowsUnsignedRequest() throws Exception {
			mockMvc.perform(get("/api/submissions").header(ServiceAuth.OWNER_HEADER, OWNER))
				.andExpect(status().isOk());
		}

		@Test
		@DisplayName("설정이 꺼진 것으로 인식된다")
		void reportsDisabled() {
			assertThat(config.enabled()).isFalse();
		}
	}
}
