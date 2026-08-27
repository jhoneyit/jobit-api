package com.jobit.video;

import com.jobit.common.OwnerKey;
import com.jobit.llm.LlmException;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 영상 요약 엔드포인트 (docs/api.md "영상 요약").
 *
 * <p><b>단건 조회만 {@code X-Owner-Key} 가 없다.</b> 보고서는 공유 링크가 목적인 전역 캐시
 * 자산이라(공고와 같다) UUID 를 아는 사람은 읽는다. 제출·목록·삭제는 개인 이력이라 필수다.
 */
@RestController
@RequestMapping(path = "/api/video-summaries", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Slf4j
public class VideoSummaryController {

	/** 답 하나는 짧지만(≤800토큰) 재부팅 직후 모델 로드가 얹히면 수 분이다 — 넉넉히 잡는다. */
	private static final long QNA_TIMEOUT_MS = 600_000L;

	private final VideoSummaryService service;

	private final VideoQnaService qnaService;

	private final VideoFrames frames;

	/** QnA 스트리밍 전용 — SseEmitter 는 요청 스레드를 반환하고 다른 스레드가 채운다. */
	private final ExecutorService qnaExecutor = Executors.newVirtualThreadPerTaskExecutor();

	/**
	 * {@code POST /api/video-summaries} — 요약을 요청한다. <b>기다리지 않는다</b> — PENDING 행을
	 * 만들고 바로 돌아오며, 프론트는 GET 으로 폴링한다. 이미 요약된 영상이면 DONE 이 바로 온다.
	 */
	@PostMapping
	public VideoView.Detail submit(@RequestHeader("X-Owner-Key") String ownerKey,
			@Valid @RequestBody VideoSummaryRequest request) {

		return VideoView.of(service.submit(OwnerKey.requireValid(ownerKey), request.url()));
	}

	/** {@code GET /api/video-summaries/{id}} — 상태 폴링 + 보고서. 공유 링크용이라 소유자 없이 읽는다. */
	@GetMapping("/{summaryId}")
	public VideoView.Detail get(@PathVariable UUID summaryId) {
		return VideoView.of(service.get(summaryId), frames.capturedSeconds(summaryId));
	}

	/**
	 * {@code POST /api/video-summaries/{id}/qna} — 영상 내용 질문 (3분할 화면의 우측 채팅).
	 * 질문 하나가 GPU 추론 하나라 소유자 한도를 소비한다.
	 *
	 * <p><b>SSE 로 흘린다</b> (2026-08-27) — 로컬 추론은 답 하나에 수십 초라, 한 번에 주면
	 * 그동안 화면이 무응답이다. 이벤트: {@code delta}(답 텍스트 조각) → {@code done}(재검증된
	 * refs 포함 최종 답) 또는 {@code error}. 검증·한도(404·409·429)는 {@code prepare} 가
	 * 스트림을 열기 전에 끝내므로 평범한 HTTP 오류로 나간다. {@code EventSource} 는 GET 만
	 * 지원하지만 이 클라이언트는 fetch 스트리밍이라 POST 그대로다 (질문·히스토리가 본문).
	 */
	// produces 에 JSON 을 병기한다 — 이벤트 스트림만 선언하면 prepare 가 던진 예외(404·409·429)의
	// JSON 응답이 그 타입에 묶여 렌더링에 실패하고 전부 500 이 된다 (실측).
	@PostMapping(path = "/{summaryId}/qna",
			produces = { MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE })
	public SseEmitter qna(@RequestHeader("X-Owner-Key") String ownerKey,
			@PathVariable UUID summaryId, @Valid @RequestBody QnaRequest request) {

		VideoQnaService.Prepared prepared = qnaService.prepare(OwnerKey.requireValid(ownerKey),
				summaryId, request.history() == null ? List.of() : request.history());
		String question = request.question().strip();

		SseEmitter emitter = new SseEmitter(QNA_TIMEOUT_MS);
		qnaExecutor.execute(() -> {
			try {
				VideoQna.Answer answer = prepared.ask(question,
						delta -> send(emitter, "delta", Map.of("text", delta)));
				send(emitter, "done", new QnaResponse(answer.answer(), answer.refs()));
				emitter.complete();
			}
			catch (ClientGoneException ex) {
				emitter.completeWithError(ex);
			}
			catch (LlmException ex) {
				fail(emitter, ex.getMessage());
			}
			catch (Exception ex) {
				log.warn("영상 QnA 실패: summary={} — {}", summaryId, ex.toString());
				fail(emitter, "답변을 받지 못했습니다. 잠시 후 다시 시도해 주세요.");
			}
		});
		return emitter;
	}

	/** 이벤트 하나를 밀어 넣는다. 끊긴 연결은 오류가 아니라 종료 신호다 (QuestionController 와 같다). */
	private void send(SseEmitter emitter, String name, Object data) {
		try {
			emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
		}
		catch (IOException | IllegalStateException ex) {
			throw new ClientGoneException(ex);
		}
	}

	private void fail(SseEmitter emitter, String message) {
		try {
			emitter.send(SseEmitter.event().name("error").data(Map.of("message", message),
					MediaType.APPLICATION_JSON));
			emitter.complete();
		}
		catch (IOException | IllegalStateException ex) {
			emitter.completeWithError(ex);
		}
	}

	private static class ClientGoneException extends RuntimeException {

		ClientGoneException(Throwable cause) {
			super(cause);
		}
	}

	/** {@code GET /api/video-summaries/{id}/frame/{startSec}} — 섹션 캡처. 없으면 404. */
	@GetMapping(value = "/{summaryId}/frame/{startSec}",
			produces = org.springframework.http.MediaType.IMAGE_JPEG_VALUE)
	public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> frame(
			@PathVariable UUID summaryId, @PathVariable int startSec) {

		return frames.resolve(summaryId, startSec)
			.<org.springframework.http.ResponseEntity<org.springframework.core.io.Resource>>map(
					path -> org.springframework.http.ResponseEntity.ok()
						.cacheControl(org.springframework.http.CacheControl
							.maxAge(java.time.Duration.ofDays(7)))
						.body(new org.springframework.core.io.FileSystemResource(path)))
			.orElseGet(() -> org.springframework.http.ResponseEntity.notFound().build());
	}

	public record QnaRequest(
			@jakarta.validation.constraints.NotBlank(message = "질문을 입력해 주세요.")
			@jakarta.validation.constraints.Size(max = 500, message = "질문이 너무 깁니다.") String question,
			@jakarta.validation.constraints.Size(max = 12, message = "대화 기록이 너무 깁니다.") List<String> history) {
	}

	public record QnaResponse(String answer, List<Integer> refs) {
	}

	@GetMapping
	public ListResponse list(@RequestHeader("X-Owner-Key") String ownerKey) {
		List<VideoView.Row> items = service.listMine(OwnerKey.requireValid(ownerKey))
			.stream()
			.map(VideoView::row)
			.toList();
		return new ListResponse(items);
	}

	/** 내 이력에서만 지운다 — 요약은 전역 캐시라 남는다 (제출 이력 삭제와 같은 규약). */
	@DeleteMapping("/{summaryId}")
	public org.springframework.http.ResponseEntity<Void> delete(
			@RequestHeader("X-Owner-Key") String ownerKey, @PathVariable UUID summaryId) {
		service.deleteSubmission(OwnerKey.requireValid(ownerKey), summaryId);
		return org.springframework.http.ResponseEntity.noContent().build();
	}

	public record ListResponse(List<VideoView.Row> items) {
	}
}
