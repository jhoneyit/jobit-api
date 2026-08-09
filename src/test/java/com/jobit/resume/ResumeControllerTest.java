package com.jobit.resume;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobit.common.ApiExceptionHandler;
import com.jobit.common.NotFoundException;
import com.jobit.common.TextCipher;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code /api/resumes} 의 계약 (docs/api.md).
 *
 * <p>이 계약은 {@code jobit-front} 가 의존하므로, 응답 형태와 에러 코드가 바뀌면 프론트가 깨진다.
 */
@WebMvcTest(ResumeController.class)
@Import(ApiExceptionHandler.class)
class ResumeControllerTest {

	private static final String OWNER = "user:u-1";

	private static final String VALID_RESUME = "결제 서버를 개발했습니다. ".repeat(5);

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ResumeService resumeService;

	@MockitoBean
	private ResumeBulletRepository bulletRepository;

	private Resume resume;

	@BeforeEach
	void setUp() {
		resume = new Resume(OWNER, "v1.암호문", null, OffsetDateTime.parse("2026-11-07T00:00:00Z"));
		ReflectionTestUtils.setField(resume, "id", UUID.randomUUID());
	}

	private String body(String text) {
		return "{\"text\":\"" + text.replace("\"", "\\\"") + "\"}";
	}

	@Test
	@DisplayName("업로드하면 문장 수와 만료 시각을 돌려준다")
	void returnsUploadResult() throws Exception {
		given(resumeService.upload(eq(OWNER), anyString()))
			.willReturn(new ResumeService.Stored(resume, 12));

		mockMvc
			.perform(post("/api/resumes").header("X-Owner-Key", OWNER)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(VALID_RESUME)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.resumeId").exists())
			.andExpect(jsonPath("$.bulletCount").value(12))
			.andExpect(jsonPath("$.expiresAt").exists());
	}

	@Test
	@DisplayName("응답에 이력서 원문이 들어가지 않는다 — 규약이지 누락이 아니다")
	void neverReturnsRawText() throws Exception {
		given(resumeService.upload(eq(OWNER), anyString()))
			.willReturn(new ResumeService.Stored(resume, 1));

		mockMvc
			.perform(post("/api/resumes").header("X-Owner-Key", OWNER)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(VALID_RESUME)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.rawText").doesNotExist())
			.andExpect(jsonPath("$.text").doesNotExist());
	}

	@Test
	@DisplayName("X-Owner-Key 가 없으면 400 — 개인 자산이라 소유자 없이 할 일이 없다")
	void requiresOwnerKey() throws Exception {
		mockMvc
			.perform(post("/api/resumes").contentType(MediaType.APPLICATION_JSON)
				.content(body(VALID_RESUME)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").exists());

		then(resumeService).should(never()).upload(anyString(), anyString());
	}

	@Test
	@DisplayName("접두사 없는 owner_key 는 400 — 조용히 0건을 돌려주지 않는다")
	void rejectsUnprefixedOwnerKey() throws Exception {
		mockMvc
			.perform(post("/api/resumes").header("X-Owner-Key", "u-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(VALID_RESUME)))
			.andExpect(status().isBadRequest());

		then(resumeService).should(never()).upload(anyString(), anyString());
	}

	@Test
	@DisplayName("너무 짧은 이력서는 400 — 사용자에게 보여줄 문구가 담긴다")
	void rejectsTooShort() throws Exception {
		mockMvc
			.perform(post("/api/resumes").header("X-Owner-Key", OWNER)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("짧음")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("50자")));
	}

	@Test
	@DisplayName("암호화 키가 없으면 500 — 평문 저장으로 폴백하지 않는다는 결정이 여기서 드러난다")
	void cipherMissingIsServerError() throws Exception {
		willThrow(new TextCipher.NotConfiguredException()).given(resumeService)
			.upload(anyString(), anyString());

		mockMvc
			.perform(post("/api/resumes").header("X-Owner-Key", OWNER)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(VALID_RESUME)))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.error").exists());
	}

	@Test
	@DisplayName("상세는 문장 목록을 돌려준다")
	void returnsDetail() throws Exception {
		ResumeBullet bullet = new ResumeBullet(resume, "토스", "2022.03 ~", "결제 서버 개발", 0);
		ReflectionTestUtils.setField(bullet, "id", UUID.randomUUID());
		given(resumeService.getOwned(any(UUID.class), eq(OWNER)))
			.willReturn(new ResumeService.Detail(resume, List.of(bullet), 1));

		mockMvc.perform(get("/api/resumes/" + resume.getId()).header("X-Owner-Key", OWNER))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.bullets[0].text").value("결제 서버 개발"))
			.andExpect(jsonPath("$.bullets[0].company").value("토스"))
			.andExpect(jsonPath("$.embeddedCount").value(1));
	}

	@Test
	@DisplayName("남의 이력서는 404 — 403이면 존재 여부가 새어 나간다")
	void othersResumeIsNotFound() throws Exception {
		willThrow(new NotFoundException("resume not found")).given(resumeService)
			.getOwned(any(UUID.class), anyString());

		mockMvc.perform(get("/api/resumes/" + UUID.randomUUID()).header("X-Owner-Key", OWNER))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("삭제는 204 를 돌려준다")
	void deleteReturnsNoContent() throws Exception {
		mockMvc.perform(delete("/api/resumes/" + resume.getId()).header("X-Owner-Key", OWNER))
			.andExpect(status().isNoContent());

		then(resumeService).should().delete(resume.getId(), OWNER);
	}

	@Test
	@DisplayName("승계는 익명 → 계정 한 방향만 허용한다")
	void claimDirectionIsEnforced() throws Exception {
		mockMvc
			.perform(post("/api/resumes/claim").header("X-Owner-Key", "anon:s-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fromOwnerKey\":\"user:u-1\"}"))
			.andExpect(status().isBadRequest());

		then(resumeService).should(never()).claim(anyString(), anyString());
	}

	@Test
	@DisplayName("승계는 옮겨진 건수를 돌려준다")
	void claimReturnsMovedCount() throws Exception {
		given(resumeService.claim("anon:s-1", OWNER)).willReturn(2);

		mockMvc
			.perform(post("/api/resumes/claim").header("X-Owner-Key", OWNER)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fromOwnerKey\":\"anon:s-1\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.moved").value(2));
	}
}
