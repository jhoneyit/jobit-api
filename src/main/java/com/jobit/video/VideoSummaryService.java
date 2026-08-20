package com.jobit.video;

import com.jobit.common.NotFoundException;
import com.jobit.llm.LlmException;
import com.jobit.llm.LlmGuard;
import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 영상 요약 오케스트레이션 (스펙 외 새 축).
 *
 * <p><b>동기 응답이 불가능한 첫 기능이다.</b> 자막 영상도 청크 요약에 수 분, STT 는 수십 분 —
 * 지금까지의 "느리지만 기다린다"(이력서 분해, 갭 분석)와 급이 다르다. 그래서 제출은 PENDING
 * 행만 만들고, 처리는 워커가, 프론트는 폴링한다.
 *
 * <p><b>워커는 하나다.</b> GPU 가 하나라 요약 둘을 겹쳐 돌리면 서로 줄만 세운다 — 단일 워커
 * 큐가 곧 GPU 큐다 (갭 분석의 "판정 직렬이 곧 GPU 를 나눠 쓰는 것" 판단의 명시적 버전).
 *
 * <p><b>재시작 복구:</b> 큐는 메모리에 있으므로 재시작하면 PENDING·RUNNING 이 고아가 된다.
 * 부팅 때 둘 다 다시 큐에 넣는다 — 중단된 처리를 다시 도는 것은 캐시 오염이 없다 (DONE 만
 * 캐시고, DONE 은 건드리지 않는다).
 */
@Service
@Slf4j
public class VideoSummaryService {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final VideoSummaryRepository summaryRepository;

	private final VideoSubmissionRepository submissionRepository;

	private final TranscriptService transcriptService;

	private final VideoSummarizer summarizer;

	private final LlmGuard llmGuard;

	private final TransactionTemplate transactionTemplate;

	private final Clock clock;

	/** 단일 워커 = GPU 큐. 가상 스레드 — 대부분을 subprocess·HTTP 대기로 보낸다. */
	private final ExecutorService worker = Executors
		.newSingleThreadExecutor(r -> Thread.ofVirtual().name("video-summary-worker").unstarted(r));

	public VideoSummaryService(VideoSummaryRepository summaryRepository,
			VideoSubmissionRepository submissionRepository, TranscriptService transcriptService,
			VideoSummarizer summarizer, LlmGuard llmGuard, TransactionTemplate transactionTemplate,
			Clock clock) {
		this.summaryRepository = summaryRepository;
		this.submissionRepository = submissionRepository;
		this.transcriptService = transcriptService;
		this.summarizer = summarizer;
		this.llmGuard = llmGuard;
		this.transactionTemplate = transactionTemplate;
		this.clock = clock;
	}

	/**
	 * 요약을 요청한다. 이미 있으면(어느 상태든) 그 행에 내 이력만 잇는다 — DONE 이면 즉시
	 * 보고서가, 진행 중이면 상태가 돌아가고, FAILED 면 재시도로 되살린다.
	 */
	public VideoSummary submit(String ownerKey, String url) {
		String videoId = VideoIds.extract(url);
		if (videoId == null) {
			throw new InvalidVideoUrlException();
		}

		Optional<VideoSummary> existing = summaryRepository.findByVideoId(videoId);
		if (existing.isPresent()) {
			VideoSummary summary = existing.get();
			link(ownerKey, summary);
			if (summary.getStatus() == VideoSummary.Status.FAILED) {
				// 재시도는 새 처리이므로 한도를 소비한다. DONE·진행 중은 소비하지 않는다.
				llmGuard.checkAndConsume(ownerKey);
				transactionTemplate.executeWithoutResult(tx -> summaryRepository
					.findById(summary.getId())
					.ifPresent(s -> s.requeue(OffsetDateTime.now(clock))));
				enqueue(summary.getId());
			}
			return summaryRepository.findById(summary.getId()).orElseThrow();
		}

		llmGuard.checkAndConsume(ownerKey);
		VideoSummary summary;
		try {
			summary = transactionTemplate.execute(tx -> summaryRepository
				.save(new VideoSummary(videoId, "https://www.youtube.com/watch?v=" + videoId,
						OffsetDateTime.now(clock))));
		}
		catch (DataIntegrityViolationException ex) {
			// 같은 영상을 동시에 넣은 경합 — 유니크 제약이 한쪽을 이겼다. 이긴 쪽에 잇는다.
			summary = summaryRepository.findByVideoId(videoId).orElseThrow(() -> ex);
			link(ownerKey, summary);
			return summary;
		}
		link(ownerKey, summary);
		enqueue(summary.getId());
		return summary;
	}

	/** 조회 — 보고서 화면 폴링용. 공유 링크가 목적이라 소유자 없이도 ID 만 알면 읽는다. */
	public VideoSummary get(UUID summaryId) {
		return summaryRepository.findById(summaryId)
			.orElseThrow(() -> new NotFoundException("video summary not found: " + summaryId));
	}

	public List<VideoSubmission> listMine(String ownerKey) {
		return submissionRepository.findForList(ownerKey);
	}

	/** 내 이력 한 줄만 지운다. 요약 자체는 전역 캐시라 남는다 (jd_submission 삭제와 같은 규약). */
	public void deleteSubmission(String ownerKey, UUID summaryId) {
		VideoSubmission submission = submissionRepository
			.findByOwnerKeyAndSummaryId(ownerKey, summaryId)
			.orElseThrow(() -> new NotFoundException("video submission not found: " + summaryId));
		submissionRepository.delete(submission);
	}

	private void link(String ownerKey, VideoSummary summary) {
		if (submissionRepository.findByOwnerKeyAndSummaryId(ownerKey, summary.getId()).isEmpty()) {
			try {
				transactionTemplate.executeWithoutResult(
						tx -> submissionRepository.save(new VideoSubmission(ownerKey, summary)));
			}
			catch (DataIntegrityViolationException ignored) {
				// 같은 사람이 두 탭에서 동시에 넣은 경합 — 이미 이어져 있으면 그걸로 충분하다.
			}
		}
	}

	/** 부팅 복구 — 메모리 큐가 사라졌으니 미완 행을 다시 줄 세운다. */
	@EventListener(ContextRefreshedEvent.class)
	public void recover() {
		List<VideoSummary> orphans = summaryRepository.findByStatusIn(
				List.of(VideoSummary.Status.PENDING, VideoSummary.Status.RUNNING));
		for (VideoSummary orphan : orphans) {
			transactionTemplate.executeWithoutResult(tx -> summaryRepository
				.findById(orphan.getId())
				.ifPresent(s -> s.requeue(OffsetDateTime.now(clock))));
			enqueue(orphan.getId());
		}
		if (!orphans.isEmpty()) {
			log.info("재시작으로 중단된 영상 요약 {}건을 다시 줄 세웠다", orphans.size());
		}
	}

	@PreDestroy
	void shutdown() {
		worker.shutdownNow();
	}

	private void enqueue(UUID summaryId) {
		worker.submit(() -> process(summaryId));
	}

	/**
	 * 실제 처리 — 워커 스레드에서만 돈다.
	 *
	 * <p>상태 전이마다 짧은 트랜잭션을 연다. 느린 작업(다운로드·STT·LLM)은 전부 트랜잭션
	 * 밖이다 — 이 흐름 하나가 수십 분인데 커넥션을 붙들면 풀이 마른다 (ResumeService 규약의 극단).
	 */
	void process(UUID summaryId) {
		VideoSummary summary = summaryRepository.findById(summaryId).orElse(null);
		if (summary == null || summary.getStatus() == VideoSummary.Status.DONE) {
			return; // 삭제됐거나 (경합으로) 이미 끝났다.
		}
		String videoId = summary.getVideoId();
		update(summaryId, s -> s.start(OffsetDateTime.now(clock)));

		try {
			TranscriptService.Result transcript = transcriptService.acquire(videoId);
			update(summaryId, s -> s.meta(transcript.meta().title(), transcript.meta().channel(),
					transcript.meta().durationSec(), OffsetDateTime.now(clock)));

			VideoReportResponse report = summarizer
				.summarize(new VideoSummarizer.Request(transcript.meta().title(),
						transcript.meta().channel(), transcript.meta().durationSec(),
						transcript.segments()));

			String json = MAPPER.writeValueAsString(report);
			VideoSummary.Source source = transcript.source() == TranscriptService.Source.CAPTION
					? VideoSummary.Source.CAPTION : VideoSummary.Source.STT;
			update(summaryId, s -> s.complete(source, json, VideoPrompts.PROMPT_VERSION,
					OffsetDateTime.now(clock)));
			log.info("영상 요약 완료: video={} source={}", videoId, source);
		}
		catch (Exception ex) {
			update(summaryId, s -> s.fail(userMessage(ex), OffsetDateTime.now(clock)));
			log.warn("영상 요약 실패: video={} — {}", videoId, ex.toString());
		}
	}

	/** error_message 는 화면에 그대로 나간다 — 내부 예외 문구를 새지 않게 여기서 고른다. */
	private static String userMessage(Exception ex) {
		if (ex instanceof TranscriptChunker.TooLongException) {
			return "영상이 너무 깁니다 (대략 4시간 이상). 더 짧은 영상으로 시도해 주세요.";
		}
		if (ex instanceof WhisperCli.SttNotConfiguredException) {
			return "이 영상에는 자막이 없고, 음성 인식이 설정되지 않았습니다. 자막 있는 영상으로 시도해 주세요.";
		}
		if (ex instanceof YtDlp.ToolNotConfiguredException
				|| ex instanceof VideoSummarizerFallbackConfig.VideoSummarizerNotConfiguredException) {
			return "영상 요약 기능이 아직 서버에 설정되지 않았습니다.";
		}
		if (ex instanceof LlmException llm) {
			return llm.getMessage();
		}
		if (ex instanceof IOException) {
			return "영상을 가져오지 못했습니다. 주소가 맞는지 확인하거나 잠시 후 다시 시도해 주세요.";
		}
		return "요약 처리 중 문제가 생겼습니다. 다시 시도해 주세요.";
	}

	private void update(UUID summaryId, java.util.function.Consumer<VideoSummary> change) {
		transactionTemplate.executeWithoutResult(
				tx -> summaryRepository.findById(summaryId).ifPresent(change));
	}

	/** 유튜브 URL 이 아니다. 사용자가 고칠 수 있는 입력 문제라 400 이다. */
	public static class InvalidVideoUrlException extends RuntimeException {

		public InvalidVideoUrlException() {
			super("유튜브 영상 주소가 아닙니다");
		}
	}
}
