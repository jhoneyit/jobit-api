package com.jobit.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.jobit.llm.LlmGuard;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 제출 흐름의 규칙 — 캐시(전역)와 한도의 관계를 고정한다.
 *
 * <p>워커 처리 자체는 여기서 보지 않는다 (연결된 목이 없으면 조용히 끝난다). 처리 경로는
 * 실영상 종단 확인이 본다.
 */
class VideoSummaryServiceTest {

	private static final String OWNER = "user:u-1";

	private static final String URL = "https://youtu.be/dQw4w9WgXcQ";

	private VideoSummaryRepository summaryRepository;

	private VideoSubmissionRepository submissionRepository;

	private LlmGuard llmGuard;

	private VideoSummaryService service;

	@BeforeEach
	void setUp() {
		summaryRepository = mock(VideoSummaryRepository.class);
		submissionRepository = mock(VideoSubmissionRepository.class);
		llmGuard = mock(LlmGuard.class);

		TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
		given(transactionTemplate.execute(any())).willAnswer(invocation -> {
			TransactionCallback<?> callback = invocation.getArgument(0);
			return callback.doInTransaction(mock(TransactionStatus.class));
		});
		Mockito.doAnswer(invocation -> {
			Consumer<TransactionStatus> callback = invocation.getArgument(0);
			callback.accept(mock(TransactionStatus.class));
			return null;
		}).when(transactionTemplate).executeWithoutResult(any());

		given(summaryRepository.findByVideoId(anyString())).willReturn(Optional.empty());
		given(summaryRepository.save(any(VideoSummary.class))).willAnswer(invocation -> {
			VideoSummary summary = invocation.getArgument(0);
			ReflectionTestUtils.setField(summary, "id", UUID.randomUUID());
			return summary;
		});
		// save 후 재조회 경로 — 저장한 그 객체를 돌려준다.
		given(summaryRepository.findById(any())).willReturn(Optional.empty());
		given(submissionRepository.findByOwnerKeyAndSummaryId(anyString(), any()))
			.willReturn(Optional.empty());

		service = new VideoSummaryService(summaryRepository, submissionRepository,
				mock(TranscriptService.class), mock(VideoSummarizer.class),
				mock(VideoChunkRepository.class), mock(com.jobit.llm.EmbeddingClient.class),
				mock(VideoFrames.class), llmGuard, transactionTemplate,
				Clock.fixed(Instant.parse("2026-08-20T10:00:00Z"), ZoneOffset.UTC), 7_200);
	}

	@Test
	@DisplayName("유튜브 주소가 아니면 한도를 소비하기 전에 끊는다")
	void rejectsNonYoutubeBeforeGuard() {
		assertThatThrownBy(() -> service.submit(OWNER, "https://vimeo.com/123"))
			.isInstanceOf(VideoSummaryService.InvalidVideoUrlException.class);

		then(llmGuard).should(never()).checkAndConsume(anyString());
	}

	@Test
	@DisplayName("새 영상은 한도를 소비하고 PENDING 으로 접수된다")
	void freshSubmitConsumesGuard() {
		VideoSummary summary = service.submit(OWNER, URL);

		assertThat(summary.getStatus()).isEqualTo(VideoSummary.Status.PENDING);
		assertThat(summary.getVideoId()).isEqualTo("dQw4w9WgXcQ");
		then(llmGuard).should().checkAndConsume(OWNER);
		then(submissionRepository).should().save(any(VideoSubmission.class));
	}

	@Test
	@DisplayName("이미 요약된 영상은 한도를 소비하지 않는다 — 전역 캐시가 목적이다")
	void doneSummarySkipsGuard() {
		VideoSummary done = new VideoSummary("dQw4w9WgXcQ", URL, null);
		ReflectionTestUtils.setField(done, "id", UUID.randomUUID());
		ReflectionTestUtils.setField(done, "status", VideoSummary.Status.DONE);
		given(summaryRepository.findByVideoId("dQw4w9WgXcQ")).willReturn(Optional.of(done));
		given(summaryRepository.findById(done.getId())).willReturn(Optional.of(done));

		VideoSummary result = service.submit(OWNER, URL);

		assertThat(result.getStatus()).isEqualTo(VideoSummary.Status.DONE);
		then(llmGuard).should(never()).checkAndConsume(anyString());
		then(submissionRepository).should().save(any(VideoSubmission.class));
	}

	@Test
	@DisplayName("FAILED 영상 재요청은 재시도다 — 한도를 소비하고 PENDING 으로 되돌린다")
	void failedSummaryRequeuesWithGuard() {
		VideoSummary failed = new VideoSummary("dQw4w9WgXcQ", URL, null);
		ReflectionTestUtils.setField(failed, "id", UUID.randomUUID());
		ReflectionTestUtils.setField(failed, "status", VideoSummary.Status.FAILED);
		given(summaryRepository.findByVideoId("dQw4w9WgXcQ")).willReturn(Optional.of(failed));
		given(summaryRepository.findById(failed.getId())).willReturn(Optional.of(failed));

		service.submit(OWNER, URL);

		then(llmGuard).should().checkAndConsume(OWNER);
		assertThat(failed.getStatus()).isEqualTo(VideoSummary.Status.PENDING);
	}

	@Test
	@DisplayName("이력 삭제는 내 줄만 지운다 — 남의 것이면 존재도 알려주지 않는다")
	void deleteRemovesOnlyMySubmission() {
		assertThatThrownBy(() -> service.deleteSubmission(OWNER, UUID.randomUUID()))
			.isInstanceOf(com.jobit.common.NotFoundException.class);
		then(summaryRepository).should(never()).delete(any());
	}
}
