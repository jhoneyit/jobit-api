package com.jobit.video;

import com.jobit.common.OwnerKey;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 영상 요약 엔드포인트 (docs/api.md "영상 요약").
 *
 * <p><b>단건 조회만 {@code X-Owner-Key} 가 없다.</b> 보고서는 공유 링크가 목적인 전역 캐시
 * 자산이라(공고와 같다) UUID 를 아는 사람은 읽는다. 제출·목록·삭제는 개인 이력이라 필수다.
 */
@RestController
@RequestMapping(path = "/api/video-summaries", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class VideoSummaryController {

	private final VideoSummaryService service;

	private final VideoQnaService qnaService;

	private final VideoFrames frames;

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
	 */
	@PostMapping("/{summaryId}/qna")
	public QnaResponse qna(@RequestHeader("X-Owner-Key") String ownerKey,
			@PathVariable UUID summaryId, @Valid @RequestBody QnaRequest request) {

		VideoQna.Answer answer = qnaService.ask(OwnerKey.requireValid(ownerKey), summaryId,
				request.question().strip(),
				request.history() == null ? List.of() : request.history());
		return new QnaResponse(answer.answer(), answer.refs());
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
