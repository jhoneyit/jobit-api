package com.jobit.video;

import com.jobit.common.NotFoundException;
import com.jobit.llm.LlmGuard;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * QnA 진입 규칙 — DONE 인 요약 + 청크가 있어야 하고, 질문마다 한도를 소비한다.
 *
 * <p>한도를 채팅에도 거는 이유: 질문 하나가 곧 GPU 추론 하나다. 캐시가 없는 기능이라
 * (같은 질문을 두 번 묻는 일은 드물고, 맞혀봐야 히스토리가 달라진다) 소비 조건이 단순하다.
 */
@Service
@RequiredArgsConstructor
public class VideoQnaService {

	/** 히스토리 상한 — 프롬프트가 무한히 자라는 것을 막는다. 오래된 맥락은 잘려도 된다. */
	static final int MAX_HISTORY = 6;

	private final VideoSummaryRepository summaryRepository;

	private final VideoChunkRepository chunkRepository;

	private final LlmGuard llmGuard;

	private final Optional<VideoQna> qna;

	public VideoQna.Answer ask(String ownerKey, UUID summaryId, String question,
			List<String> history) {

		VideoSummary summary = summaryRepository.findById(summaryId)
			.orElseThrow(() -> new NotFoundException("video summary not found: " + summaryId));
		if (summary.getStatus() != VideoSummary.Status.DONE) {
			throw new QnaUnavailableException("요약이 끝난 뒤에 질문할 수 있습니다.");
		}
		if (!chunkRepository.existsForSummary(summaryId)) {
			// V16 이전에 요약된 영상 — 자막 청크가 없다. 재요약하면 생긴다.
			throw new QnaUnavailableException("이 요약에는 질문 기능이 없습니다. 영상을 다시 요약하면 쓸 수 있습니다.");
		}

		VideoQna engine = qna.orElseThrow(() -> new QnaUnavailableException(
				"질문 기능이 아직 서버에 설정되지 않았습니다."));

		llmGuard.checkAndConsume(ownerKey);

		List<String> trimmed = history == null ? List.of()
				: history.subList(Math.max(0, history.size() - MAX_HISTORY), history.size());
		return engine.ask(summaryId, summary.getTitle(), question, trimmed);
	}

	/** 사용자가 지금 할 수 없는 상태 — 문구가 다음 행동을 말한다. 409 로 나간다. */
	public static class QnaUnavailableException extends RuntimeException {

		public QnaUnavailableException(String message) {
			super(message);
		}
	}
}
