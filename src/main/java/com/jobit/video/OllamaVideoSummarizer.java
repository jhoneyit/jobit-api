package com.jobit.video;

import com.jobit.llm.JsonSchemas;
import com.jobit.llm.LlmCallRecorder;
import com.jobit.llm.LlmException;
import com.jobit.llm.LlmFeature;
import com.jobit.llm.LlmModelConfig;
import com.jobit.llm.OllamaChatClient;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 2단계 map-reduce 요약 (스펙 외 새 축 — 영상 요약).
 *
 * <pre>
 * 자막 → 청크 분할 → 청크마다 요약 (LOW, 청크 수만큼) → 보고서 통합 (HIGH, 한 번)
 * </pre>
 *
 * <p>청크 요약이 실패하면 <b>그 청크만 건너뛴다</b> — 1시간 영상의 한 구간이 죽었다고 전체를
 * 버리면 사용자가 잃는 것이 더 크다. 대신 몇 개를 건너뛰었는지 세고, 절반을 넘으면 그때는
 * 보고서가 거짓이 되므로 실패로 올린다.
 *
 * <p>보고서 통합은 {@code OllamaRewriter} 와 같은 재시도 규약이다 — 검증
 * ({@link VideoReportNormalizer#problem}) 실패만 한 번 다시 묻는다.
 */
@Component
@ConditionalOnProperty(name = "ollama.base-url")
@RequiredArgsConstructor
@Slf4j
public class OllamaVideoSummarizer implements VideoSummarizer {

	private static final int MAX_REPORT_ATTEMPTS = 2;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final OllamaChatClient client;

	private final LlmCallRecorder callRecorder;

	@Override
	public VideoReportResponse summarize(Request request) {
		List<TranscriptChunker.Chunk> chunks = TranscriptChunker.chunk(request.segments());
		if (chunks.isEmpty()) {
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
					"영상에서 요약할 내용을 찾지 못했습니다.");
		}

		List<Integer> starts = new ArrayList<>();
		List<String> summaries = new ArrayList<>();
		int skipped = 0;
		for (int i = 0; i < chunks.size(); i++) {
			TranscriptChunker.Chunk chunk = chunks.get(i);
			try {
				ChunkSummaryResponse summary = call(LlmFeature.VIDEO_CHUNK,
						VideoPrompts.CHUNK_SYSTEM,
						VideoPrompts.chunkMessage(request.title(), chunk.startSec(), chunk.text()),
						ChunkSummaryResponse.class);
				if (summary.summary() != null && !summary.summary().isBlank()) {
					starts.add(chunk.startSec());
					summaries.add(summary.summary().strip());
				}
				else {
					skipped++;
				}
			}
			catch (LlmException ex) {
				skipped++;
				log.warn("청크 {}/{} 요약 실패 — 건너뛴다: {}", i + 1, chunks.size(), ex.getMessage());
			}
			log.debug("영상 청크 요약 {}/{}", i + 1, chunks.size());
		}

		if (summaries.isEmpty() || skipped > chunks.size() / 2) {
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
					"영상 내용을 충분히 요약하지 못했습니다. 잠시 후 다시 시도해 주세요.");
		}
		if (skipped > 0) {
			log.warn("청크 {}개 중 {}개를 건너뛰고 보고서를 만든다", chunks.size(), skipped);
		}

		String lastProblem = null;
		for (int attempt = 1; attempt <= MAX_REPORT_ATTEMPTS; attempt++) {
			VideoReportResponse report = call(LlmFeature.VIDEO_REPORT, VideoPrompts.REPORT_SYSTEM,
					VideoPrompts.reportMessage(request.title(), request.channel(),
							request.durationSec(), starts, summaries),
					VideoReportResponse.class);

			String problem = VideoReportNormalizer.problem(report);
			if (problem == null) {
				return VideoReportNormalizer.normalize(report, request.durationSec());
			}
			lastProblem = problem;
			log.warn("보고서 응답이 검증에 실패했습니다 ({}/{}): {}", attempt, MAX_REPORT_ATTEMPTS, problem);
		}
		log.warn("보고서 재시도 소진: {}", lastProblem);
		throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
				"보고서를 만들지 못했습니다. 잠시 후 다시 시도해 주세요.");
	}

	private <T> T call(LlmFeature feature, String system, String user, Class<T> type) {
		LlmModelConfig.FeatureConfig config = LlmModelConfig.of(feature);

		OllamaChatClient.Completion completion;
		try {
			completion = client.chat(new OllamaChatClient.Request(config.model(), system, user,
					JsonSchemas.of(type), config.effort(), config.maxTokens()));
		}
		catch (OllamaChatClient.OllamaCallException ex) {
			throw new LlmException(LlmException.Kind.UPSTREAM,
					"요약 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.", ex);
		}

		callRecorder.record(feature, config.model(), completion.usage().inputTokens(),
				completion.usage().outputTokens(), false, completion.latencyMs());

		if (completion.content() == null || completion.content().isBlank()) {
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE, "요약 결과를 받지 못했습니다.");
		}
		try {
			return MAPPER.readValue(completion.content(), type);
		}
		catch (JacksonException ex) {
			// 자막은 공개 콘텐츠지만 응답 조각을 예외에 실을 이유도 없다 — 원인 없이 던진다.
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE, "요약 결과를 받지 못했습니다.");
		}
	}
}
