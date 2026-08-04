package com.jobit.jd;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobit.common.ApiExceptionHandler;
import com.jobit.llm.LlmException;
import com.jobit.submission.JdSubmissionService;
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
 * {@code POST /api/jd/parse}의 계약 (docs/api.md).
 *
 * <p>이 계약은 {@code jobit-front}가 의존하므로, 응답 형태와 에러 코드가 바뀌면 프론트가 깨진다.
 */
@WebMvcTest(JdController.class)
@Import(ApiExceptionHandler.class)
class JdControllerTest {

	private static final String VALID_JD = "백엔드 개발자를 채용합니다. ".repeat(10);

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private JdParsingService parsingService;

	@MockitoBean
	private JdSubmissionService submissionService;

	private JobPosting posting;

	@BeforeEach
	void setUp() {
		posting = new JobPosting("hash", VALID_JD, null, "토스", "백엔드 개발자",
				"{\"stack\":[\"Java\"]}");
		ReflectionTestUtils.setField(posting, "id", UUID.randomUUID());
	}

	private String body(String text) {
		return "{\"text\":\"" + text.replace("\"", "\\\"") + "\"}";
	}

	@Test
	@DisplayName("파싱 결과를 JSON으로 돌려준다")
	void returnsParseResult() throws Exception {
		given(parsingService.parseOrGetCached(anyString(), any(), any()))
			.willReturn(new JdParsingService.Outcome(posting, false));
		given(parsingService.requirementsOf(posting)).willReturn(List.of());

		mockMvc.perform(post("/api/jd/parse").contentType(MediaType.APPLICATION_JSON)
			.content(body(VALID_JD)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.jobPostingId").exists())
			.andExpect(jsonPath("$.company").value("토스"))
			.andExpect(jsonPath("$.cached").value(false))
			// parsed 는 이미 JSON 문자열이라 다시 이스케이프되면 안 된다.
			.andExpect(jsonPath("$.parsed.stack[0]").value("Java"));
	}

	@Test
	@DisplayName("캐시 재사용이면 cached=true — 프론트가 레이트 리밋 소비 여부를 판단한다")
	void reportsCacheHit() throws Exception {
		given(parsingService.parseOrGetCached(anyString(), any(), any()))
			.willReturn(new JdParsingService.Outcome(posting, true));
		given(parsingService.requirementsOf(posting)).willReturn(List.of());

		mockMvc.perform(post("/api/jd/parse").contentType(MediaType.APPLICATION_JSON)
			.content(body(VALID_JD))).andExpect(jsonPath("$.cached").value(true));
	}

	@Test
	@DisplayName("본문이 짧으면 400 — LLM을 부르지 않는다")
	void rejectsTooShortText() throws Exception {
		mockMvc.perform(post("/api/jd/parse").contentType(MediaType.APPLICATION_JSON)
			.content(body("짧음")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").exists());

		then(parsingService).should(never()).parseOrGetCached(anyString(), any(), any());
	}

	@Test
	@DisplayName("잘못된 JSON은 400이고 에러 형태는 동일하다")
	void rejectsMalformedBody() throws Exception {
		mockMvc.perform(
				post("/api/jd/parse").contentType(MediaType.APPLICATION_JSON).content("{not json"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("잘못된 요청 형식입니다."));
	}

	@Test
	@DisplayName("제공자 레이트 리밋은 429로 내린다")
	void mapsRateLimitTo429() throws Exception {
		given(parsingService.parseOrGetCached(anyString(), any(), any())).willThrow(
				new LlmException(LlmException.Kind.RATE_LIMIT, "요청이 몰려 잠시 처리할 수 없습니다."));

		mockMvc.perform(post("/api/jd/parse").contentType(MediaType.APPLICATION_JSON)
			.content(body(VALID_JD)))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.error").value("요청이 몰려 잠시 처리할 수 없습니다."));
	}

	@Test
	@DisplayName("LLM 장애는 502로 내린다 — 사용자 잘못이 아니다")
	void mapsUpstreamTo502() throws Exception {
		given(parsingService.parseOrGetCached(anyString(), any(), any()))
			.willThrow(new LlmException(LlmException.Kind.UPSTREAM, "잠시 후 다시 시도해 주세요."));

		mockMvc.perform(post("/api/jd/parse").contentType(MediaType.APPLICATION_JSON)
			.content(body(VALID_JD))).andExpect(status().isBadGateway());
	}

	@Test
	@DisplayName("X-Owner-Key가 있으면 입력 이력에 남긴다")
	void recordsSubmissionWhenOwnerKeyPresent() throws Exception {
		given(parsingService.parseOrGetCached(anyString(), any(), any()))
			.willReturn(new JdParsingService.Outcome(posting, false));
		given(parsingService.requirementsOf(posting)).willReturn(List.of());

		mockMvc.perform(post("/api/jd/parse").contentType(MediaType.APPLICATION_JSON)
			.header("X-Owner-Key", "user:abc123")
			.content(body(VALID_JD))).andExpect(status().isOk());

		then(submissionService).should().record(eq("user:abc123"), eq(posting));
	}

	@Test
	@DisplayName("X-Owner-Key가 없으면 이력을 남기지 않는다 — 비로그인도 파싱은 된다")
	void skipsSubmissionWithoutOwnerKey() throws Exception {
		given(parsingService.parseOrGetCached(anyString(), any(), any()))
			.willReturn(new JdParsingService.Outcome(posting, false));
		given(parsingService.requirementsOf(posting)).willReturn(List.of());

		mockMvc.perform(post("/api/jd/parse").contentType(MediaType.APPLICATION_JSON)
			.content(body(VALID_JD))).andExpect(status().isOk());

		then(submissionService).should(never()).record(anyString(), any());
	}

	@Test
	@DisplayName("이력 기록이 실패해도 파싱 결과는 정상 반환한다")
	void submissionFailureDoesNotFailRequest() throws Exception {
		given(parsingService.parseOrGetCached(anyString(), any(), any()))
			.willReturn(new JdParsingService.Outcome(posting, false));
		given(parsingService.requirementsOf(posting)).willReturn(List.of());
		given(submissionService.record(anyString(), any()))
			.willThrow(new IllegalStateException("DB 장애"));

		mockMvc.perform(post("/api/jd/parse").contentType(MediaType.APPLICATION_JSON)
			.header("X-Owner-Key", "user:abc123")
			.content(body(VALID_JD))).andExpect(status().isOk());
	}

	@Test
	@DisplayName("형식이 틀린 owner_key는 이력만 건너뛴다 — 파싱은 성공한다")
	void invalidOwnerKeyDoesNotFailRequest() throws Exception {
		given(parsingService.parseOrGetCached(anyString(), any(), any()))
			.willReturn(new JdParsingService.Outcome(posting, false));
		given(parsingService.requirementsOf(posting)).willReturn(List.of());

		mockMvc.perform(post("/api/jd/parse").contentType(MediaType.APPLICATION_JSON)
			.header("X-Owner-Key", "접두사없음")
			.content(body(VALID_JD))).andExpect(status().isOk());

		then(submissionService).should(never()).record(anyString(), any());
	}
}
