package com.jobit.submission;

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
import com.jobit.gap.GapSummary;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code /api/submissions}의 계약 (docs/api.md).
 *
 * <p>이 계약은 {@code jobit-front}의 "내 기록" 화면이 의존한다 — 응답 형태나 에러 코드가 바뀌면
 * 그쪽이 깨진다. 특히 <b>남의 이력에 404를 준다</b>는 규칙은 여기서만 고정된다.
 */
@WebMvcTest(SubmissionController.class)
@Import(ApiExceptionHandler.class)
class SubmissionControllerTest {

	private static final String OWNER = "user:abc123";

	private static final UUID SUBMISSION_ID = UUID.randomUUID();

	private static final UUID JOB_POSTING_ID = UUID.randomUUID();

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private JdSubmissionService submissionService;

	private SubmissionListItem item(GapSummary gapSummary) {
		return new SubmissionListItem(SUBMISSION_ID, JOB_POSTING_ID, "토스", "백엔드 개발자",
				"{\"stack\":[\"Java\"],\"domain\":\"핀테크 결제\"}", null, 12, 10,
				OffsetDateTime.parse("2026-08-07T09:12:33Z"), gapSummary);
	}

	private void givenOneItem(GapSummary gapSummary) {
		given(submissionService.list(eq(OWNER), any(Pageable.class)))
			.willReturn(new PageImpl<>(List.of(item(gapSummary)), PageRequest.of(0, 20), 1));
	}

	@Test
	@DisplayName("목록을 화면이 쓰는 형태로 돌려준다")
	void returnsList() throws Exception {
		givenOneItem(null);

		mockMvc.perform(get("/api/submissions").header("X-Owner-Key", OWNER))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].submissionId").value(SUBMISSION_ID.toString()))
			.andExpect(jsonPath("$.items[0].jobPostingId").value(JOB_POSTING_ID.toString()))
			.andExpect(jsonPath("$.items[0].company").value("토스"))
			.andExpect(jsonPath("$.items[0].requirementCount").value(12))
			.andExpect(jsonPath("$.items[0].questionCount").value(10))
			// parsed 는 이미 JSON 문자열이라 다시 이스케이프되면 안 된다 (JdParseResult 와 같은 규약).
			.andExpect(jsonPath("$.items[0].parsed.stack[0]").value("Java"))
			.andExpect(jsonPath("$.items[0].parsed.domain").value("핀테크 결제"))
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.page").value(0));
	}

	@Test
	@DisplayName("갭 분석 전이면 gapSummary 는 null — 0/0/0 으로 채우지 않는다")
	void leavesGapSummaryNullBeforeAnalysis() throws Exception {
		givenOneItem(null);

		mockMvc.perform(get("/api/submissions").header("X-Owner-Key", OWNER))
			.andExpect(jsonPath("$.items[0].gapSummary").doesNotExist());
	}

	@Test
	@DisplayName("분석했으면 gapSummary 가 실려 온다 — 위 테스트와 짝이다")
	void carriesGapSummaryAfterAnalysis() throws Exception {
		givenOneItem(new GapSummary(JOB_POSTING_ID, 8, 3, 2));

		mockMvc.perform(get("/api/submissions").header("X-Owner-Key", OWNER))
			.andExpect(jsonPath("$.items[0].gapSummary.met").value(8))
			.andExpect(jsonPath("$.items[0].gapSummary.missing").value(2));
	}

	@Test
	@DisplayName("X-Owner-Key 가 없으면 400 — 빈 목록으로 얼버무리지 않는다")
	void requiresOwnerKey() throws Exception {
		mockMvc.perform(get("/api/submissions"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").exists());

		then(submissionService).should(never()).list(anyString(), any());
	}

	@Test
	@DisplayName("접두사 없는 owner_key 는 400 — 조회가 조용히 0건을 반환하면 규약 위반이 묻힌다")
	void rejectsOwnerKeyWithoutPrefix() throws Exception {
		mockMvc.perform(get("/api/submissions").header("X-Owner-Key", "접두사없음"))
			.andExpect(status().isBadRequest());

		then(submissionService).should(never()).list(anyString(), any());
	}

	@Test
	@DisplayName("size 는 1~100 으로 잘린다 — 목록 하나로 서버를 끌어내리지 못하게 한다")
	void clampsPageSize() throws Exception {
		given(submissionService.list(eq(OWNER), any(Pageable.class)))
			.willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

		mockMvc.perform(get("/api/submissions").header("X-Owner-Key", OWNER).param("size", "5000"))
			.andExpect(status().isOk());

		then(submissionService).should().list(OWNER, PageRequest.of(0, 100));
	}

	@Test
	@DisplayName("삭제는 204 이고 본문이 없다")
	void deletesSubmission() throws Exception {
		mockMvc.perform(delete("/api/submissions/" + SUBMISSION_ID).header("X-Owner-Key", OWNER))
			.andExpect(status().isNoContent());

		then(submissionService).should().delete(SUBMISSION_ID, OWNER);
	}

	@Test
	@DisplayName("남의 이력 삭제는 403 이 아니라 404 — 403 이면 존재 여부가 새어 나간다")
	void hidesOtherOwnersSubmission() throws Exception {
		willThrow(new NotFoundException("submission not found")).given(submissionService)
			.delete(eq(SUBMISSION_ID), anyString());

		mockMvc.perform(delete("/api/submissions/" + SUBMISSION_ID).header("X-Owner-Key", OWNER))
			.andExpect(status().isNotFound())
			// 내부 문구("submission not found: <uuid>")가 그대로 화면에 뜨면 안 된다.
			.andExpect(jsonPath("$.error").value("찾을 수 없습니다."));
	}

	@Test
	@DisplayName("UUID 가 아닌 경로 변수는 400 — 기본 동작인 500 이면 서버 장애로 보인다")
	void rejectsMalformedId() throws Exception {
		mockMvc.perform(delete("/api/submissions/not-a-uuid").header("X-Owner-Key", OWNER))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").exists());
	}

	@Test
	@DisplayName("승계는 옮겨진 줄 수를 돌려준다")
	void claimsAnonymousHistory() throws Exception {
		given(submissionService.transferOwnership("anon:sess-1", OWNER)).willReturn(3);

		mockMvc
			.perform(post("/api/submissions/claim").header("X-Owner-Key", OWNER)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fromOwnerKey\":\"anon:sess-1\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.moved").value(3));
	}

	@Test
	@DisplayName("계정 → 익명 방향의 승계는 막는다 — 계정 기록을 익명 키로 빼내는 경로가 된다")
	void rejectsReverseClaim() throws Exception {
		mockMvc
			.perform(post("/api/submissions/claim").header("X-Owner-Key", "anon:sess-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fromOwnerKey\":\"" + OWNER + "\"}"))
			.andExpect(status().isBadRequest());

		then(submissionService).should(never()).transferOwnership(anyString(), anyString());
	}

	@Test
	@DisplayName("승계 대상이 계정이어도 출처가 익명이 아니면 막는다")
	void rejectsClaimFromNonAnonymousSource() throws Exception {
		mockMvc
			.perform(post("/api/submissions/claim").header("X-Owner-Key", OWNER)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fromOwnerKey\":\"user:other\"}"))
			.andExpect(status().isBadRequest());

		then(submissionService).should(never()).transferOwnership(anyString(), anyString());
	}

	@Test
	@DisplayName("fromOwnerKey 가 비어 있으면 400")
	void rejectsBlankClaimSource() throws Exception {
		mockMvc
			.perform(post("/api/submissions/claim").header("X-Owner-Key", OWNER)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isBadRequest());

		then(submissionService).should(never()).transferOwnership(anyString(), anyString());
	}
}
