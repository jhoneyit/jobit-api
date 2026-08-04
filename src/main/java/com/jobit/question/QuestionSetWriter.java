package com.jobit.question;

import com.jobit.jd.JobPosting;
import com.jobit.jd.Requirement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 질문 세트 저장 전담.
 *
 * <p><b>왜 {@link QuestionService} 안의 메서드가 아닌가.</b> Spring의 {@code @Transactional}은
 * 프록시로 동작하므로 <b>같은 빈 안에서 부르면 적용되지 않는다.</b> 생성 흐름이
 * {@code generateOrGetCached() → save()} 라 자기호출이 되고, 그러면 세트와 질문이 서로 다른
 * 트랜잭션에 저장돼 스트림이 끊겼을 때 질문 없는 빈 세트가 남는다. 빈을 나눠 프록시를 태운다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QuestionSetWriter {

	private final QuestionSetRepository questionSetRepository;

	private final QuestionRepository questionRepository;

	/**
	 * 세트와 질문을 한 트랜잭션에 저장한다.
	 *
	 * <p>유니크 제약({@code job_posting_id, prompt_version})에 걸리면 동시 요청이 먼저 저장한
	 * 것이므로 <b>그쪽 id를 쓴다.</b> 실패로 취급하면 사용자는 이미 화면에서 본 질문을 잃는다.
	 */
	@Transactional
	public UUID save(JobPosting posting, List<Requirement> requirements,
			List<PendingQuestion> pending, String model) {
		try {
			QuestionSet set = questionSetRepository
				.save(new QuestionSet(posting, QuestionGenPrompts.PROMPT_VERSION, model));

			List<Question> entities = new ArrayList<>(pending.size());
			for (PendingQuestion p : pending) {
				entities.add(new Question(set, requirementOrNull(requirements, p.requirementId()),
						p.text(), p.category(), p.difficulty(), p.followupsJson(),
						p.answerOutlineJson(), p.sortOrder()));
			}
			questionRepository.saveAll(entities);
			return set.getId();
		}
		catch (DataIntegrityViolationException ex) {
			log.info("질문 세트가 이미 있습니다 (동시 요청). 기존 세트를 사용합니다: {}",
					posting.getId());
			return questionSetRepository
				.findByJobPostingIdAndPromptVersion(posting.getId(),
						QuestionGenPrompts.PROMPT_VERSION)
				.map(QuestionSet::getId)
				.orElseThrow(() -> ex);
		}
	}

	private Requirement requirementOrNull(List<Requirement> requirements, UUID id) {
		if (id == null) {
			return null;
		}
		return requirements.stream().filter(r -> r.getId().equals(id)).findFirst().orElse(null);
	}

	/** 저장 대기 중인 질문. 스트리밍 중에는 아직 엔티티가 아니다. */
	public record PendingQuestion(UUID requirementId, String text, Question.Category category,
			short difficulty, String followupsJson, String answerOutlineJson, int sortOrder) {
	}
}
