package com.jobit.question;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobit.jd.JobPosting;
import com.jobit.jd.JobPostingRepository;
import com.jobit.jd.Requirement;
import com.jobit.jd.RequirementRepository;
import com.jobit.llm.LlmCallRecorder;
import com.jobit.llm.LlmException;
import com.jobit.llm.LlmFeature;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 질문 생성 오케스트레이션 (스펙 §4.2).
 *
 * <p>순서는 <b>캐시 → 생성 → 저장</b>이다. {@code prompt_version}이 같으면 재생성하지 않는다.
 *
 * <p><b>저장은 스트리밍이 끝난 뒤 한 번에 한다.</b> 질문이 나올 때마다 INSERT 하면 스트림이 중간에
 * 끊겼을 때 반쪽짜리 세트가 캐시에 남고, {@code prompt_version} 이 같은 한 그 반쪽이 계속
 * 재사용된다. 그래서 중단 시에는 <b>저장하지 않고</b> 다음 요청에 다시 만든다 — 이미 흘려보낸
 * 질문은 사용자가 그대로 보되, 캐시는 오염시키지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuestionService {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final JobPostingRepository jobPostingRepository;

	private final RequirementRepository requirementRepository;

	private final QuestionSetRepository questionSetRepository;

	private final QuestionRepository questionRepository;

	private final QuestionSetWriter writer;

	private final LlmCallRecorder callRecorder;

	/** API 키가 없으면 이 빈이 없다. 그 경우 호출 시점에 명확한 예외를 던진다. */
	private final Optional<QuestionGenerator> generator;

	/**
	 * 질문을 생성하거나 캐시를 재사용한다.
	 *
	 * @param onQuestion 질문 하나가 확정될 때마다 호출된다 (캐시 적중 시에도 같은 콜백으로 흐른다)
	 */
	public Outcome generateOrGetCached(UUID jobPostingId, Consumer<QuestionView> onQuestion) {
		JobPosting posting = jobPostingRepository.findById(jobPostingId)
			.orElseThrow(() -> new PostingNotFoundException(jobPostingId));

		List<Requirement> requirements = requirementRepository
			.findByJobPostingIdOrderBySortOrder(jobPostingId);
		if (requirements.isEmpty()) {
			// 요구사항이 없으면 질문의 앵커가 없다. 파싱이 잘못 끝난 공고다.
			throw new PostingNotFoundException(jobPostingId);
		}

		Optional<QuestionSet> cached = questionSetRepository
			.findByJobPostingIdAndPromptVersion(jobPostingId, QuestionGenPrompts.PROMPT_VERSION);
		if (cached.isPresent()) {
			List<QuestionView> views = loadViews(cached.get().getId());
			views.forEach(onQuestion);
			return new Outcome(cached.get().getId(), views.size(), true);
		}

		QuestionGenerator gen = generator.orElseThrow(QuestionGeneratorNotConfiguredException::new);

		List<QuestionSetWriter.PendingQuestion> pending = new ArrayList<>();
		Map<String, Object> parsedMeta = readParsed(posting);

		QuestionGenerator.Result result;
		try {
			result = gen.generate(parsedMeta, requirements, raw -> {
				QuestionSetWriter.PendingQuestion p = toPending(raw, requirements, pending.size());
				pending.add(p);
				onQuestion.accept(toView(p));
			});
		}
		catch (LlmException ex) {
			if (pending.isEmpty()) {
				throw ex;
			}
			log.warn("질문 생성이 중단됐지만 {}개는 전달됐습니다. 저장하지 않아 다음 요청에 재생성됩니다.",
					pending.size());
			return new Outcome(null, pending.size(), false);
		}

		// 재시도가 아니라 한 번의 스트림이지만, 돈이 나갔으므로 성공·실패와 무관하게 기록한다.
		callRecorder.record(LlmFeature.QUESTION_GEN, result.model(), result.usage().inputTokens(),
				result.usage().outputTokens(), result.usage().cacheReadTokens(),
				result.usage().cacheCreationTokens(), false, result.latencyMs());

		UUID setId = writer.save(posting, requirements, pending, result.model());
		return new Outcome(setId, pending.size(), false);
	}

	@Transactional(readOnly = true)
	public List<QuestionView> loadViews(UUID questionSetId) {
		return questionRepository.findForDisplay(questionSetId).stream().map(QuestionView::of)
			.toList();
	}

	/** 모델이 준 번호를 실제 요구사항에 잇는다. 범위를 벗어나거나 -1이면 매핑 없음. */
	private UUID requirementIdAt(List<Requirement> requirements, Integer index) {
		if (index == null || index < 0 || index >= requirements.size()) {
			return null;
		}
		return requirements.get(index).getId();
	}

	private QuestionSetWriter.PendingQuestion toPending(QuestionGenResponse.RawQuestion raw,
			List<Requirement> requirements, int sortOrder) {
		return new QuestionSetWriter.PendingQuestion(
				requirementIdAt(requirements, raw.requirementIndex()), raw.text().strip(),
				raw.category(), raw.difficulty().shortValue(), toJson(raw.safeFollowups()),
				toJson(raw.safeAnswerOutline()), sortOrder);
	}

	/** 스트리밍 중에는 아직 저장 전이라 id가 없다. 화면은 sortOrder로 key를 잡는다. */
	private QuestionView toView(QuestionSetWriter.PendingQuestion p) {
		return new QuestionView(null, p.requirementId(), p.text(), p.category(), p.difficulty(),
				p.followupsJson(), p.answerOutlineJson(), p.sortOrder());
	}

	/** {@code job_posting.parsed} 는 jsonb 문자열이다. 프롬프트에 쓰려면 풀어야 한다. */
	private Map<String, Object> readParsed(JobPosting posting) {
		try {
			return MAPPER.readValue(posting.getParsed(), new TypeReference<Map<String, Object>>() {
			});
		}
		catch (JsonProcessingException ex) {
			// 메타데이터가 없어도 요구사항만으로 질문은 만들 수 있다. 여기서 멈추지 않는다.
			log.warn("job_posting.parsed 를 읽지 못했습니다. 메타데이터 없이 진행합니다: {}",
					posting.getId());
			return Map.of();
		}
	}

	private String toJson(List<String> values) {
		try {
			return MAPPER.writeValueAsString(values);
		}
		catch (JsonProcessingException ex) {
			return "[]";
		}
	}

	/** @param questionSetId 저장에 실패했거나 중단됐으면 null */
	public record Outcome(UUID questionSetId, int count, boolean cached) {
	}

	public static class PostingNotFoundException extends RuntimeException {

		public PostingNotFoundException(UUID id) {
			super("공고를 찾을 수 없습니다: " + id);
		}
	}

	public static class QuestionGeneratorNotConfiguredException extends IllegalStateException {

		public QuestionGeneratorNotConfiguredException() {
			super("LLM 클라이언트가 없어 질문을 생성할 수 없습니다. ANTHROPIC_API_KEY 를 확인해 주세요.");
		}
	}
}
