package com.jobit.jd;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code requirement.embedding} 전용 리포지토리 (스펙 §5 5단계 — 질문 은행).
 *
 * <p>JPA 가 아닌 이유는 {@code ResumeBulletEmbeddingRepository} 와 같다 — 이 컬럼의 사용처가
 * 유사도 검색 하나뿐이라 엔티티 매핑은 1024개짜리 배열을 실어 나르는 일만 만든다.
 * {@link Requirement} 는 이 컬럼을 모른 채로 둔다.
 */
@Repository
@RequiredArgsConstructor
public class RequirementEmbeddingRepository {

	private final JdbcClient jdbc;

	/** 요구사항 하나의 벡터를 저장한다. {@code ::vector} 캐스트가 필요한 이유는 이력서 쪽 참고. */
	public void updateEmbedding(UUID requirementId, float[] embedding) {
		jdbc.sql("update requirement set embedding = cast(:embedding as vector) where id = :id")
			.param("embedding", toVectorLiteral(embedding))
			.param("id", requirementId)
			.update();
	}

	/**
	 * 이 요구사항과 유사한 <b>다른 공고</b> 요구사항에서 나온 질문들 (스펙 §5 — 유사 공고 질문 추천).
	 *
	 * <p><b>self-join 으로 벡터 왕복을 없앤다.</b> 저장된 벡터를 Java 로 꺼내 다시 보내면 1024개
	 * 실수를 문자열로 두 번 나르게 된다 — 원본 요구사항의 벡터를 SQL 안에서 그대로 참조한다.
	 *
	 * <p><b>자기 공고를 제외한다.</b> 같은 공고의 이웃 요구사항이 가장 가깝게 마련인데, 그쪽
	 * 질문은 지금 생성에서 함께 만들어지는 것들이라 참고가 아니라 순환이다.
	 *
	 * <p><b>임계값이 상한(limit)보다 중요하다.</b> 은행이 작을 때 상위 N 개는 "가장 덜 먼 것"일
	 * 뿐 비슷하다는 보장이 없다 — 무관한 질문을 참고로 넣으면 없느니만 못하다. 충분히 가까운
	 * 것이 없으면 빈 목록이 정답이다.
	 *
	 * @param minSimilarity 코사인 유사도 하한 (1에 가까울수록 가깝다)
	 */
	public List<BankedQuestion> findSimilarQuestions(UUID requirementId, double minSimilarity,
			int limit) {
		return jdbc.sql("""
				select q.text, 1 - (other.embedding <=> src.embedding) as similarity
				from requirement src
				join requirement other
				  on other.job_posting_id <> src.job_posting_id
				 and other.embedding is not null
				join question q on q.requirement_id = other.id
				where src.id = :requirementId and src.embedding is not null
				  and 1 - (other.embedding <=> src.embedding) >= :minSimilarity
				order by other.embedding <=> src.embedding
				limit :limit
				""")
			.param("requirementId", requirementId)
			.param("minSimilarity", minSimilarity)
			.param("limit", limit)
			.query((rs, rowNum) -> new BankedQuestion(rs.getString("text"),
					rs.getDouble("similarity")))
			.list();
	}

	/** pgvector 리터럴. 구현·이유는 {@code ResumeBulletEmbeddingRepository#toVectorLiteral} 참고. */
	static String toVectorLiteral(float[] embedding) {
		if (embedding == null || embedding.length == 0) {
			throw new IllegalArgumentException("embedding must not be empty");
		}
		StringBuilder sb = new StringBuilder(embedding.length * 12 + 2);
		sb.append('[');
		for (int i = 0; i < embedding.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(embedding[i]);
		}
		return sb.append(']').toString();
	}

	/**
	 * @param similarity 원본 요구사항과의 코사인 유사도. 정렬·컷오프 판단의 근거로 로그에 남긴다
	 */
	public record BankedQuestion(String text, double similarity) {
	}
}
