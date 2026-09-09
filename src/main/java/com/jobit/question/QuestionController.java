package com.jobit.question;

import com.jobit.llm.LlmException;
import com.jobit.llm.LlmGuard;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 질문 생성 SSE 엔드포인트 (스펙 §4.2, docs/api.md).
 *
 * <p>{@code GET /api/questions?jobPostingId=...} — 브라우저의 {@code EventSource} 가 직접 받는다
 * (CLAUDE.md 스택 결정). 완성된 질문이 나올 때마다 하나씩 흘려보내므로 사용자는 십수 초를 빈 화면으로
 * 기다리지 않는다.
 *
 * <p><b>왜 별도 스레드인가.</b> {@link SseEmitter} 는 요청 스레드를 즉시 반환하고 다른 스레드가
 * 이벤트를 밀어 넣는 구조다. 컨트롤러 스레드에서 LLM 을 기다리면 서블릿 스레드가 수 분간 묶인다.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class QuestionController {

	/**
	 * SSE 수명 상한. <b>로컬 추론 실측으로 다시 잡았다</b> (2026-08-15) — 5분이던 시절은
	 * Anthropic 실측 62초 기준이었는데, 로컬 qwen3:14b 는 thinking 을 켠 질문 생성이
	 * 8~13 tok/s 로 10분을 넘겨 5분 타임아웃이 **정상 생성을 구조적으로 끊었다**
	 * (질문 0개 시점에 끊기면 세트도 저장되지 않아, 재시도해도 같은 자리에서 또 끊긴다).
	 * thinking 동안은 이벤트가 없어 조용한 연결이 길다 — 프록시를 앞에 두면 idle 컷을
	 * 이 값보다 길게 잡아야 한다.
	 */
	private static final long TIMEOUT_MS = 1_200_000L;

	private final QuestionService questionService;

	/**
	 * 가상 스레드. 대부분의 시간을 네트워크 대기로 보내는 작업이라 플랫폼 스레드를 묶어 둘 이유가 없다.
	 * 동시 접속이 늘어도 스레드 풀 크기를 튜닝할 필요가 없다.
	 */
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	/**
	 * thinking 동안(첫 질문까지 1분+)은 이벤트가 하나도 없다 — 그 침묵이 리버스 프록시의
	 * 유휴 컷(nginx 기본 60초)보다 길면 정상 생성이 끊긴다. SSE 주석(":")을 주기적으로 흘려
	 * 회선만 살린다 — 주석은 클라이언트 파서가 버리므로 화면 쪽 변경이 없다.
	 */
	private static final long HEARTBEAT_MS = 15_000L;

	private final ScheduledExecutorService heartbeats = Executors
		.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

	@GetMapping(path = "/api/questions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream(@RequestParam UUID jobPostingId,
			@RequestHeader(name = "X-Owner-Key", required = false) String ownerKey) {
		SseEmitter emitter = new SseEmitter(TIMEOUT_MS);

		ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(() -> {
			try {
				// send() 는 질문 스레드와 경합하므로 emitter 로 직렬화한다 (아래 send 와 같은 락).
				synchronized (emitter) {
					emitter.send(SseEmitter.event().comment("keep-alive"));
				}
			}
			catch (IOException | IllegalStateException ex) {
				// 끊긴 연결 — 본 작업이 다음 send 에서 알게 되므로 여기서는 조용히 둔다.
			}
		}, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
		emitter.onCompletion(() -> heartbeat.cancel(false));
		emitter.onTimeout(() -> heartbeat.cancel(false));
		emitter.onError(ex -> heartbeat.cancel(false));

		executor.execute(() -> {
			try {
				QuestionService.Outcome outcome = questionService.generateOrGetCached(jobPostingId,
						ownerKey, q -> send(emitter, "question", Map.of("question", q)));

				send(emitter, "done", Map.of("count", outcome.count(), "questionSetId",
						outcome.questionSetId() == null ? "" : outcome.questionSetId().toString(),
						"cached", outcome.cached()));
				emitter.complete();
			}
			catch (ClientGoneException ex) {
				// 사용자가 탭을 닫았다. 오류가 아니다 — LLM 호출은 이미 과금됐으므로
				// llm_call_log 에는 남지만, 여기서 더 할 일은 없다.
				log.debug("클라이언트가 연결을 끊었습니다: jobPostingId={}", jobPostingId);
				emitter.complete();
			}
			catch (LlmGuard.RateLimitExceededException ex) {
				// SSE 는 이미 200 으로 헤더가 나갔으므로 @ExceptionHandler 가 끼어들 수 없다.
				// 상태 코드 대신 error 이벤트로 알린다.
				long minutes = Math.max(1, ex.getRetryAfterSeconds() / 60);
				fail(emitter, "요청 한도를 초과했습니다. %d분 뒤에 다시 시도해 주세요.".formatted(minutes));
			}
			catch (LlmGuard.DailyBudgetExceededException ex) {
				fail(emitter, ex.getMessage());
			}
			catch (QuestionService.PostingNotFoundException ex) {
				fail(emitter, "공고를 찾을 수 없습니다. 공고를 다시 붙여넣어 주세요.");
			}
			catch (LlmException ex) {
				fail(emitter, ex.getMessage());
			}
			catch (QuestionService.QuestionGeneratorNotConfiguredException ex) {
				log.error("질문 생성이 설정되지 않았습니다", ex);
				fail(emitter, "질문 생성을 사용할 수 없습니다. 서버 설정을 확인해 주세요.");
			}
			catch (RuntimeException ex) {
				log.error("질문 생성 중 예상치 못한 오류: jobPostingId={}", jobPostingId, ex);
				fail(emitter, "질문 생성 중 오류가 발생했습니다.");
			}
		});

		return emitter;
	}

	/**
	 * 이벤트 하나를 밀어 넣는다.
	 *
	 * <p>사용자가 탭을 닫으면 여기서 {@link IOException} 이 난다. <b>정상적인 상황이므로 에러로
	 * 남기지 않는다</b> — 다만 그 뒤로 계속 밀어 넣어 봐야 소용없으므로 예외로 바꿔 흐름을 끊는다.
	 */
	private void send(SseEmitter emitter, String name, Object data) {
		try {
			// 하트비트 스레드와 경합한다 — SseEmitter.send 는 동시 호출을 보장하지 않는다.
			synchronized (emitter) {
				emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
			}
		}
		catch (IOException | IllegalStateException ex) {
			throw new ClientGoneException(ex);
		}
	}

	private void fail(SseEmitter emitter, String message) {
		try {
			synchronized (emitter) {
				emitter.send(SseEmitter.event().name("error").data(Map.of("message", message),
						MediaType.APPLICATION_JSON));
			}
			emitter.complete();
		}
		catch (IOException | IllegalStateException ex) {
			// 이미 끊긴 연결이다. 더 할 수 있는 게 없다.
			emitter.completeWithError(ex);
		}
	}

	/** 클라이언트가 먼저 끊었다. 오류가 아니라 종료 신호다. */
	private static class ClientGoneException extends RuntimeException {

		ClientGoneException(Throwable cause) {
			super("클라이언트가 연결을 끊었습니다", cause);
		}
	}
}
