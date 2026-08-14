package com.jobit.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.jobit.jd.Requirement;
import com.jobit.jd.RequirementEmbeddingRepository;
import com.jobit.jd.RequirementEmbeddingRepository.BankedQuestion;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 참고 질문 수집 규칙 (스펙 §5).
 *
 * <p>고정하는 성질: 중복은 한 번만, 전체 상한을 넘지 않는다, 은행이 비면 빈 목록이다 —
 * 셋 다 어긋나도 예외가 없어서 프롬프트 품질로만 드러난다.
 */
class QuestionReferencesTest {

	private RequirementEmbeddingRepository repository;

	private QuestionReferences references;

	@BeforeEach
	void setUp() {
		repository = mock(RequirementEmbeddingRepository.class);
		given(repository.findSimilarQuestions(any(), anyDouble(), anyInt()))
			.willReturn(List.of());
		references = new QuestionReferences(repository);
	}

	private Requirement requirement(UUID id) {
		Requirement requirement = mock(Requirement.class);
		given(requirement.getId()).willReturn(id);
		return requirement;
	}

	@Test
	@DisplayName("은행이 비어 있으면 빈 목록이다 — 참고 없이도 아무것도 나빠지지 않아야 한다")
	void emptyBankYieldsEmptyReferences() {
		assertThat(references.collect(List.of(requirement(UUID.randomUUID())))).isEmpty();
	}

	@Test
	@DisplayName("같은 질문이 여러 요구사항에서 잡혀도 한 번만 싣는다")
	void deduplicatesAcrossRequirements() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		given(repository.findSimilarQuestions(a, QuestionReferences.MIN_SIMILARITY,
				QuestionReferences.PER_REQUIREMENT))
			.willReturn(List.of(new BankedQuestion("겹치는 질문", 0.95)));
		given(repository.findSimilarQuestions(b, QuestionReferences.MIN_SIMILARITY,
				QuestionReferences.PER_REQUIREMENT))
			.willReturn(List.of(new BankedQuestion("겹치는 질문", 0.94),
					new BankedQuestion("다른 질문", 0.9)));

		assertThat(references.collect(List.of(requirement(a), requirement(b))))
			.containsExactly("겹치는 질문", "다른 질문");
	}

	@Test
	@DisplayName("전체 상한을 넘지 않는다 — 참고는 기준이지 재료가 아니다")
	void respectsTotalCap() {
		List<Requirement> requirements = new java.util.ArrayList<>();
		for (int i = 0; i < QuestionReferences.TOTAL_CAP; i++) {
			UUID id = UUID.randomUUID();
			int n = i;
			given(repository.findSimilarQuestions(id, QuestionReferences.MIN_SIMILARITY,
					QuestionReferences.PER_REQUIREMENT))
				.willReturn(List.of(new BankedQuestion("질문 " + n + "-1", 0.9),
						new BankedQuestion("질문 " + n + "-2", 0.85)));
			requirements.add(requirement(id));
		}

		assertThat(references.collect(requirements)).hasSize(QuestionReferences.TOTAL_CAP);
	}
}
