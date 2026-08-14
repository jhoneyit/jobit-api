package com.jobit.jd;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobit.PostgresTestContainer;
import com.jobit.question.Question;
import com.jobit.question.QuestionRepository;
import com.jobit.question.QuestionSet;
import com.jobit.question.QuestionSetRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code requirement.embedding} 과 질문 은행 검색이 실제 Postgres 에서 동작하는지 (스펙 §5).
 *
 * <p>{@code ResumeEmbeddingPersistenceTest} 와 같은 이유로 존재한다 — 여기서 틀리기 쉬운 것은
 * 전부 SQL 문자열 안에 있고, 컴파일러가 봐주지 않는다. 여기서 고정하는 성질은 셋이다:
 * <b>자기 공고는 나오지 않는다 / 임계값 아래는 나오지 않는다 / 가까운 것이 먼저 나온다.</b>
 */
@SpringBootTest
@Import(PostgresTestContainer.class)
@Transactional
class RequirementEmbeddingPersistenceTest {

	/** {@code requirement.embedding vector(1024)} 와 같아야 한다 (V13). */
	private static final int DIMENSIONS = 1024;

	@Autowired
	private JobPostingRepository jobPostingRepository;

	@Autowired
	private RequirementRepository requirementRepository;

	@Autowired
	private RequirementEmbeddingRepository embeddingRepository;

	@Autowired
	private QuestionSetRepository questionSetRepository;

	@Autowired
	private QuestionRepository questionRepository;

	@Autowired
	private EntityManager entityManager;

	private Requirement source;

	/** 두 성분을 섞은 단위 벡터 — 코사인 유사도를 예측 가능하게 만든다. */
	private static float[] vector(int index, int otherIndex, float otherWeight) {
		float[] v = new float[DIMENSIONS];
		v[index] = 1f;
		if (otherIndex >= 0) {
			v[otherIndex] = otherWeight;
		}
		return v;
	}

	@BeforeEach
	void setUp() {
		JobPosting mine = jobPostingRepository
			.save(new JobPosting("hash-mine", "본문", null, "우리회사", "백엔드", "{}"));
		JobPosting other = jobPostingRepository
			.save(new JobPosting("hash-other", "본문", null, "남의회사", "백엔드", "{}"));

		source = save(mine, "RDBMS 스키마 설계와 쿼리 튜닝 경험", 0);
		// 자기 공고의 이웃 — 완전히 같은 벡터라도 결과에 나오면 안 된다.
		Requirement sibling = save(mine, "우리 공고의 다른 요구사항", 1);
		// 다른 공고: 가까운 것(유사도 ≈ 0.995), 덜 가까운 것(≈ 0.89), 무관한 것(0.0)
		Requirement near = save(other, "RDBMS 스키마 설계 및 튜닝", 0);
		Requirement mid = save(other, "데이터베이스 운영 경험", 1);
		Requirement far = save(other, "디자인 시스템 구축", 2);

		entityManager.flush(); // JdbcClient UPDATE 가 INSERT 를 보게 한다

		embeddingRepository.updateEmbedding(source.getId(), vector(0, -1, 0f));
		embeddingRepository.updateEmbedding(sibling.getId(), vector(0, -1, 0f));
		embeddingRepository.updateEmbedding(near.getId(), vector(0, 1, 0.1f));
		embeddingRepository.updateEmbedding(mid.getId(), vector(0, 1, 0.5f));
		embeddingRepository.updateEmbedding(far.getId(), vector(2, -1, 0f));
		// 임베딩이 없는 요구사항 — 검색에서 조용히 빠져야 한다.
		Requirement unembedded = save(other, "임베딩 없는 요구사항", 3);

		QuestionSet otherSet = questionSetRepository.save(new QuestionSet(other, "v", "m"));
		QuestionSet mineSet = questionSetRepository.save(new QuestionSet(mine, "v", "m"));
		question(otherSet, near, "인덱스 설계에서 겪은 트레이드오프는?");
		question(otherSet, mid, "슬로우 쿼리를 어떻게 찾았나요?");
		question(otherSet, far, "디자인 토큰을 어떻게 관리했나요?");
		question(otherSet, unembedded, "임베딩 없는 요구사항의 질문");
		question(mineSet, sibling, "자기 공고 질문 — 나오면 안 된다");
		entityManager.flush();
	}

	private Requirement save(JobPosting posting, String text, int sortOrder) {
		return requirementRepository
			.save(new Requirement(posting, text, Requirement.Kind.REQUIRED, new String[0],
					sortOrder));
	}

	private void question(QuestionSet set, Requirement requirement, String text) {
		questionRepository.save(new Question(set, requirement, text, Question.Category.STACK,
				(short) 3, "[]", "[]", 0));
	}

	@Test
	@DisplayName("가까운 요구사항의 질문이 먼저 나오고, 자기 공고와 임계값 아래는 나오지 않는다")
	void findsSimilarQuestionsFromOtherPostings() {
		List<RequirementEmbeddingRepository.BankedQuestion> found = embeddingRepository
			.findSimilarQuestions(source.getId(), 0.80, 10);

		assertThat(found).extracting(RequirementEmbeddingRepository.BankedQuestion::text)
			.as("가까운 순서여야 하고, 자기 공고 질문·무관한 질문·임베딩 없는 질문은 빠져야 한다")
			.containsExactly("인덱스 설계에서 겪은 트레이드오프는?", "슬로우 쿼리를 어떻게 찾았나요?");
		assertThat(found.get(0).similarity()).isGreaterThan(found.get(1).similarity());
	}

	@Test
	@DisplayName("임계값을 올리면 덜 가까운 것부터 떨어진다 — 은행이 작을 때 무관한 참고를 막는 선이다")
	void thresholdCutsOffLooseMatches() {
		List<RequirementEmbeddingRepository.BankedQuestion> found = embeddingRepository
			.findSimilarQuestions(source.getId(), 0.95, 10);

		assertThat(found).extracting(RequirementEmbeddingRepository.BankedQuestion::text)
			.containsExactly("인덱스 설계에서 겪은 트레이드오프는?");
	}

	@Test
	@DisplayName("원본에 임베딩이 없으면 빈 목록이다 — V13 이전에 파싱된 공고가 그렇다")
	void returnsEmptyForUnembeddedSource() {
		Requirement legacy = save(
				jobPostingRepository.save(new JobPosting("hash-legacy", "본문", null, null, null,
						"{}")),
				"임베딩 없는 원본", 0);
		entityManager.flush();

		assertThat(embeddingRepository.findSimilarQuestions(legacy.getId(), 0.5, 10)).isEmpty();
	}
}
