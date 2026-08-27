package com.jobit.video;

import com.jobit.llm.EmbeddingClient;
import com.jobit.llm.JsonSchemas;
import com.jobit.llm.LlmCallRecorder;
import com.jobit.llm.LlmException;
import com.jobit.llm.LlmFeature;
import com.jobit.llm.LlmModelConfig;
import com.jobit.llm.OllamaChatClient;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 영상 QnA — 질문 임베딩 → 청크 검색 → 발췌 근거 답변 (RAG).
 *
 * <p>인터페이스+폴백 쌍 대신 {@code Optional} 주입을 쓴다 ({@code QuestionService} 의 generator
 * 와 같은 패턴) — 채팅은 캐시 개념이 없어 "설정 없이 굴려볼 부분"이 없다.
 *
 * <p><b>refs 는 서버가 재검증한다.</b> 모델이 발췌에 없는 시각을 지어내면 플레이어가 엉뚱한
 * 장면으로 튄다 — 발췌 목록에 실제로 있는 값만 남긴다.
 */
@Component
@ConditionalOnProperty(name = "ollama.base-url")
@RequiredArgsConstructor
@Slf4j
public class VideoQna {

	/** 발췌 수. 4개 × ~1,000자 ≈ 3천 토큰 — 질문·히스토리를 더해도 num_ctx 에 여유가 있다. */
	static final int EXCERPT_COUNT = 4;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final OllamaChatClient client;

	private final EmbeddingClient embeddingClient;

	private final VideoChunkRepository chunkRepository;

	private final LlmCallRecorder callRecorder;

	public record Answer(String answer, List<Integer> refs) {
	}

	/**
	 * @param onAnswerDelta 답 텍스트가 만들어지는 대로 조각조각 받는다. 구조화 출력이라 JSON
	 *        전체는 끝나야 파싱되지만, {@code answer} 필드의 내용만은
	 *        {@link com.jobit.llm.JsonStringFieldStream} 이 흐르는 중에 뽑아 준다 — refs 는
	 *        스트림이 끝난 뒤 재검증을 거쳐 반환값에만 실린다
	 */
	public Answer ask(UUID summaryId, String title, String question, List<String> history,
			java.util.function.Consumer<String> onAnswerDelta) {
		List<float[]> vectors = embeddingClient.embedAll(List.of(question));
		if (vectors.isEmpty()) {
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
					"질문을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");
		}

		List<VideoChunkRepository.Retrieved> excerpts = chunkRepository
			.findNearest(summaryId, vectors.getFirst(), EXCERPT_COUNT);
		if (excerpts.isEmpty()) {
			// 청크 유무는 호출부가 먼저 확인한다 — 여기 왔다면 임베딩이 전부 null 인 비정상이다.
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
					"이 영상에서는 질문 기능을 쓸 수 없습니다.");
		}

		LlmModelConfig.FeatureConfig config = LlmModelConfig.of(LlmFeature.VIDEO_QNA);
		// 스트림 완료 객체의 content 는 비어 있다 — 원문은 우리가 모은다 (질문 생성과 같은 규약).
		StringBuilder raw = new StringBuilder();
		com.jobit.llm.JsonStringFieldStream answerField =
				new com.jobit.llm.JsonStringFieldStream("answer", onAnswerDelta);
		OllamaChatClient.Completion completion;
		try {
			completion = client.stream(new OllamaChatClient.Request(config.model(),
					VideoQnaPrompts.SYSTEM,
					VideoQnaPrompts.userMessage(title, excerpts, history, question),
					JsonSchemas.of(VideoQnaResponse.class), config.effort(), config.maxTokens()),
					delta -> {
						raw.append(delta);
						answerField.feed(delta);
					});
		}
		catch (OllamaChatClient.OllamaCallException ex) {
			throw new LlmException(LlmException.Kind.UPSTREAM,
					"답변 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.", ex);
		}

		callRecorder.record(LlmFeature.VIDEO_QNA, config.model(), completion.usage().inputTokens(),
				completion.usage().outputTokens(), false, completion.latencyMs());

		VideoQnaResponse response;
		try {
			response = MAPPER.readValue(raw.toString(), VideoQnaResponse.class);
		}
		catch (JacksonException ex) {
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
					"답변을 받지 못했습니다. 잠시 후 다시 시도해 주세요.");
		}
		if (response.answer() == null || response.answer().isBlank()) {
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
					"답변을 받지 못했습니다. 잠시 후 다시 시도해 주세요.");
		}

		// 근거 재검증 — 발췌에 실제로 있는 시각만 통과한다.
		Set<Integer> valid = excerpts.stream()
			.map(VideoChunkRepository.Retrieved::startSec)
			.collect(Collectors.toSet());
		List<Integer> refs = response.refs() == null ? List.of()
				: response.refs().stream().filter(valid::contains).distinct().toList();

		return new Answer(response.answer().strip(), refs);
	}
}
