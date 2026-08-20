package com.jobit.video;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주제 게이트의 흐름 규칙 — 무관 판정이 REJECTED 로 남고, 요약을 타지 않는 것을 고정한다.
 *
 * <p>워커를 직접 부른다 ({@code process}) — 큐를 통하면 비동기라 단정할 수 없다.
 */
class VideoGateFlowTest {

	private VideoSummaryRepository summaryRepository;

	private TranscriptService transcriptService;

	private VideoSummarizer summarizer;

	private VideoSummaryService service;

	private VideoSummary summary;

	@BeforeEach
	void setUp() throws Exception {
		summaryRepository = mock(VideoSummaryRepository.class);
		transcriptService = mock(TranscriptService.class);
		summarizer = mock(VideoSummarizer.class);

		TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
		Mockito.doAnswer(invocation -> {
			Consumer<TransactionStatus> callback = invocation.getArgument(0);
			callback.accept(mock(TransactionStatus.class));
			return null;
		}).when(transactionTemplate).executeWithoutResult(any());

		summary = new VideoSummary("vid12345678", "url", null);
		ReflectionTestUtils.setField(summary, "id", UUID.randomUUID());
		given(summaryRepository.findById(summary.getId())).willReturn(Optional.of(summary));

		YtDlp.Meta meta = new YtDlp.Meta("음악 모음", "뮤직채널", 300, "신나는 플레이리스트");
		given(transcriptService.acquire(anyString(), any())).willAnswer(invocation -> {
			// 실제 서비스처럼 STT 직전 훅을 부른다 — 자막 없는 영상 시나리오.
			Consumer<YtDlp.Meta> beforeStt = invocation.getArgument(1);
			beforeStt.accept(meta);
			return new TranscriptService.Result(meta,
					List.of(new TranscriptSegment(0, "음악입니다")), TranscriptService.Source.STT);
		});

		service = new VideoSummaryService(summaryRepository,
				mock(VideoSubmissionRepository.class), transcriptService, summarizer,
				mock(LlmGuard.class), transactionTemplate,
				Clock.fixed(Instant.parse("2026-08-20T10:00:00Z"), ZoneOffset.UTC));
	}

	@Test
	@DisplayName("메타 단계에서 무관이면 REJECTED — STT 도 요약도 타지 않는다")
	void metaRejectionStopsBeforeStt() {
		given(summarizer.judgeRelevance(any(), any()))
			.willReturn(new VideoRelevanceResponse(false, "음악 재생목록이다"));

		service.process(summary.getId());

		assertThat(summary.getStatus()).isEqualTo(VideoSummary.Status.REJECTED);
		assertThat(summary.getErrorMessage()).contains("면접·취업").contains("음악 재생목록이다");
		then(summarizer).should(never()).summarize(any());
	}

	@Test
	@DisplayName("메타는 통과했는데 내용이 무관이면 그때 REJECTED — 낚시 제목 방어")
	void contentRejectionAfterTranscript() {
		given(summarizer.judgeRelevance(any(), Mockito.isNull()))
			.willReturn(new VideoRelevanceResponse(true, "제목만으로 확실치 않다"));
		given(summarizer.judgeRelevance(any(), anyString()))
			.willReturn(new VideoRelevanceResponse(false, "내용이 음악 감상이다"));

		service.process(summary.getId());

		assertThat(summary.getStatus()).isEqualTo(VideoSummary.Status.REJECTED);
		then(summarizer).should(never()).summarize(any());
	}

	@Test
	@DisplayName("두 판정을 통과하면 요약으로 이어진다")
	void relevantVideoProceedsToSummary() {
		given(summarizer.judgeRelevance(any(), any()))
			.willReturn(new VideoRelevanceResponse(true, "기술 면접 관련이다"));
		given(summarizer.summarize(any())).willReturn(new VideoReportResponse("한 줄", "개요",
				List.of(new VideoReportResponse.Section("구간", 0, "내용")), List.of("정리")));

		service.process(summary.getId());

		assertThat(summary.getStatus()).isEqualTo(VideoSummary.Status.DONE);
		then(summarizer).should().summarize(any());
	}
}
