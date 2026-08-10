package com.jobit.resume;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobit.PostgresTestContainer;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code resume_bullet.embedding} 이 실제 Postgres 에서 동작하는지 (스펙 §4.3 1단계).
 *
 * <p><b>이 테스트가 없으면 pgvector 경로는 아무도 검증하지 않는다.</b> {@code contextLoads()} 의
 * {@code ddl-auto=validate} 는 컬럼이 있는지까지만 보는데, 이 컬럼은 애초에 엔티티에 매핑되어
 * 있지도 않다. 그리고 여기서 틀리기 쉬운 것들은 전부 <b>SQL 문자열 안에</b> 있다 —
 * {@code ::vector} 캐스트, {@code <=>} 연산자, 리터럴 형식, 차원 수. 넷 다 컴파일러가 봐주지
 * 않고, 셋은 런타임에 예외조차 나지 않고 그냥 틀린 순서를 돌려준다.
 *
 * <p>즉 여기서 고정하는 것은 매핑이 아니라 <b>"가까운 문장이 실제로 먼저 나온다"</b>는 성질이다.
 */
@SpringBootTest
@Import(PostgresTestContainer.class)
@Transactional
class ResumeEmbeddingPersistenceTest {

	/** {@code resume_bullet.embedding vector(1024)} 와 같아야 한다 (V11). 하나만 틀려도 Postgres 가 거부한다. */
	private static final int DIMENSIONS = 1024;

	@Autowired
	private ResumeRepository resumeRepository;

	@Autowired
	private ResumeBulletRepository bulletRepository;

	@Autowired
	private ResumeBulletEmbeddingRepository embeddingRepository;

	@Autowired
	private EntityManager entityManager;

	private Resume resume;

	private ResumeBullet kafka;

	private ResumeBullet payment;

	private ResumeBullet onboarding;

	/** {@code index} 자리만 1인 단위 벡터. 코사인 유사도가 예측 가능해진다. */
	private static float[] unitVector(int index) {
		float[] vector = new float[DIMENSIONS];
		vector[index] = 1f;
		return vector;
	}

	@BeforeEach
	void setUp() {
		resume = resumeRepository
			.save(new Resume("user:u-1", "v1.암호문", null, OffsetDateTime.now().plusDays(90)));

		kafka = new ResumeBullet(resume, "토스", "2022.03 ~", "Kafka 컨슈머 중복 처리", 0);
		payment = new ResumeBullet(resume, "토스", "2022.03 ~", "결제 서버 개발", 1);
		onboarding = new ResumeBullet(resume, "토스", "2023.01 ~", "신규 입사자 온보딩 담당", 2);

		// JdbcClient 는 영속성 컨텍스트를 보지 않는다 — flush 하지 않으면 갱신할 행이 없다.
		bulletRepository.saveAllAndFlush(List.of(kafka, payment, onboarding));

		embeddingRepository.updateEmbedding(kafka.getId(), unitVector(0));
		embeddingRepository.updateEmbedding(payment.getId(), unitVector(1));
		embeddingRepository.updateEmbedding(onboarding.getId(), unitVector(2));
	}

	@Test
	@DisplayName("벡터가 실제로 저장된다 — ::vector 캐스트와 리터럴 형식이 맞는다")
	void storesVectors() {
		assertThat(embeddingRepository.countWithEmbedding(resume.getId())).isEqualTo(3);
	}

	@Test
	@DisplayName("가장 가까운 문장이 먼저 나온다 — <=> 는 거리라서 부호를 뒤집어야 한다")
	void nearestFirst() {
		List<ResumeBulletEmbeddingRepository.Neighbor> found = embeddingRepository
			.findNearest(resume.getId(), unitVector(1), 3);

		assertThat(found).extracting(ResumeBulletEmbeddingRepository.Neighbor::text)
			.first()
			.isEqualTo("결제 서버 개발");
		assertThat(found.get(0).similarity()).isCloseTo(1.0,
				org.assertj.core.data.Offset.offset(1e-5));
	}

	@Test
	@DisplayName("limit 으로 후보를 3개만 추린다 — 이게 전수 LLM 호출을 막는 지점이다")
	void limitsCandidates() {
		assertThat(embeddingRepository.findNearest(resume.getId(), unitVector(0), 2)).hasSize(2);
	}

	@Test
	@DisplayName("직교 벡터의 유사도는 0이다 — 근거 없는 문장이 후보로 올라와도 점수로 걸러진다")
	void orthogonalScoresZero() {
		List<ResumeBulletEmbeddingRepository.Neighbor> found = embeddingRepository
			.findNearest(resume.getId(), unitVector(0), 3);

		assertThat(found).hasSize(3);
		assertThat(found.get(0).similarity()).isCloseTo(1.0,
				org.assertj.core.data.Offset.offset(1e-5));
		assertThat(found.get(1).similarity()).isCloseTo(0.0,
				org.assertj.core.data.Offset.offset(1e-5));
	}

	@Test
	@DisplayName("남의 이력서 문장은 섞이지 않는다")
	void scopedToResume() {
		Resume other = resumeRepository
			.save(new Resume("user:u-2", "v1.암호문", null, OffsetDateTime.now().plusDays(90)));
		ResumeBullet otherBullet = new ResumeBullet(other, null, null, "남의 결제 서버 개발", 0);
		bulletRepository.saveAllAndFlush(List.of(otherBullet));
		embeddingRepository.updateEmbedding(otherBullet.getId(), unitVector(1));

		List<ResumeBulletEmbeddingRepository.Neighbor> found = embeddingRepository
			.findNearest(resume.getId(), unitVector(1), 10);

		assertThat(found).extracting(ResumeBulletEmbeddingRepository.Neighbor::text)
			.doesNotContain("남의 결제 서버 개발");
	}

	@Test
	@DisplayName("임베딩이 없는 문장은 후보에 오르지 않는다")
	void skipsBulletsWithoutEmbedding() {
		ResumeBullet noVector = new ResumeBullet(resume, null, null, "벡터가 없는 문장", 3);
		bulletRepository.saveAllAndFlush(List.of(noVector));

		List<ResumeBulletEmbeddingRepository.Neighbor> found = embeddingRepository
			.findNearest(resume.getId(), unitVector(1), 10);

		assertThat(found).hasSize(3)
			.extracting(ResumeBulletEmbeddingRepository.Neighbor::text)
			.doesNotContain("벡터가 없는 문장");
	}

	@Test
	@DisplayName("이력서를 지우면 문장과 벡터도 함께 사라진다 (on delete cascade)")
	void cascadeDelete() {
		// **clear() 가 필요하다.** cascade 는 DB 제약이지 JPA 설정이 아니라서, 영속성 컨텍스트에
		// 남아 있는 ResumeBullet 들은 여전히 지워질 Resume 를 참조한다. 그 상태로 flush 하면
		// Hibernate 가 "삭제된 엔티티를 참조한다"며 막는다 — DB 가 할 일을 JPA 가 먼저 거부하는
		// 셈이라, 컨텍스트를 비워야 실제 cascade 동작을 볼 수 있다.
		entityManager.flush();
		entityManager.clear();

		resumeRepository.delete(resumeRepository.findById(resume.getId()).orElseThrow());
		entityManager.flush();

		assertThat(bulletRepository.findByResumeIdOrderBySortOrder(resume.getId())).isEmpty();
		assertThat(embeddingRepository.countWithEmbedding(resume.getId())).isZero();
	}
}
