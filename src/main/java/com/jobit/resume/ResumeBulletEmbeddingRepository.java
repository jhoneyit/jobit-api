package com.jobit.resume;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code resume_bullet.embedding} 전용 리포지토리 (스펙 §4.3 1단계).
 *
 * <p><b>왜 JPA 가 아닌가.</b> {@code vector(1536)} 은 JPA 표준 타입이 아니다. Hibernate 커스텀
 * 타입을 만들 수도 있지만, 이 컬럼의 <b>실제 사용처가 코사인 유사도 상위 N개 조회 하나뿐</b>이라
 * 얻는 것보다 드는 품이 크다 — 엔티티에 매핑해 봐야 1536개짜리 배열을 메모리로 실어 나르는 일만
 * 생기고, 정작 필요한 {@code <=>} 연산자는 JPQL 로 표현되지 않아 어차피 네이티브 쿼리가 된다.
 * 그래서 {@link ResumeBullet} 엔티티는 이 컬럼을 모른 채로 두고, 벡터는 여기서만 다룬다.
 *
 * <p><b>인덱스가 없다.</b> 지금은 {@code (resume_id)} 로 좁힌 뒤 전수 스캔한다 — 이력서 하나가
 * 문장 수십 개라 정확한 계산이 근사 인덱스보다 빠르고, 무엇보다 정확하다. HNSW/IVFFlat 은
 * 문장 수가 만 단위가 되는 <b>질문 은행(스펙 §5 5단계)</b>에서 필요해지며, 그때는 이력서 안이
 * 아니라 전역에서 찾게 되므로 조회 형태 자체가 달라진다.
 */
@Repository
@RequiredArgsConstructor
public class ResumeBulletEmbeddingRepository {

	private final JdbcClient jdbc;

	/**
	 * 문장 하나의 벡터를 저장한다.
	 *
	 * <p><b>{@code ::vector} 캐스트가 필요하다.</b> JDBC 로는 문자열로 넘어가므로 명시하지 않으면
	 * {@code text} 를 {@code vector} 컬럼에 넣으려다 실패한다.
	 */
	public void updateEmbedding(UUID bulletId, float[] embedding) {
		jdbc.sql("update resume_bullet set embedding = cast(:embedding as vector) where id = :id")
			.param("embedding", toVectorLiteral(embedding))
			.param("id", bulletId)
			.update();
	}

	/**
	 * 요구사항 벡터와 가장 가까운 문장 상위 {@code limit}개 (스펙 §4.3 1단계).
	 *
	 * <p><b>이 메서드가 "요구사항 × 문장 전수 LLM 호출 금지"(작업 원칙)를 성립시킨다.</b>
	 * 요구사항 20개 × 문장 30개 = 600번의 판정 호출을, 요구사항당 후보 3개만 남겨 20번으로 줄인다.
	 *
	 * <p>{@code <=>} 는 pgvector 의 <b>코사인 거리</b> 연산자다 (0 = 완전히 같은 방향,
	 * 2 = 정반대). 유사도로 뒤집어 반환하는 이유는 호출부가 "클수록 가깝다"로 읽는 편이
	 * 자연스럽고, 임계값을 걸 때 부호를 헷갈리지 않기 때문이다.
	 *
	 * <p><b>{@code embedding is not null} 조건이 중요하다.</b> 임베딩 저장이 부분 실패한 이력서에서
	 * 이 조건을 빠뜨리면 NULL 행의 거리가 NULL 로 나와 정렬 맨 뒤로 밀리는 대신, 후보 자리를
	 * 조용히 차지할 수 있다.
	 */
	public List<Neighbor> findNearest(UUID resumeId, float[] query, int limit) {
		return jdbc.sql("""
				select id, text, company, period, 1 - (embedding <=> cast(:query as vector)) as similarity
				from resume_bullet
				where resume_id = :resumeId and embedding is not null
				order by embedding <=> cast(:query as vector)
				limit :limit
				""")
			.param("query", toVectorLiteral(query))
			.param("resumeId", resumeId)
			.param("limit", limit)
			.query((rs, rowNum) -> new Neighbor(rs.getObject("id", UUID.class), rs.getString("text"),
					rs.getString("company"), rs.getString("period"), rs.getDouble("similarity")))
			.list();
	}

	/** 임베딩이 실제로 채워진 문장 수. 부분 실패를 사후에 확인하는 용도다. */
	public int countWithEmbedding(UUID resumeId) {
		return jdbc
			.sql("select count(*) from resume_bullet where resume_id = :resumeId and embedding is not null")
			.param("resumeId", resumeId)
			.query(Integer.class)
			.single();
	}

	/**
	 * pgvector 리터럴 {@code [0.1,0.2,...]}.
	 *
	 * <p>{@code StringBuilder} 를 쓰는 이유는 취향이 아니라 크기다 — 1536개를 문자열 연결로
	 * 이으면 문장 하나당 중간 문자열이 1536개 생긴다.
	 */
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
	 * @param similarity 코사인 유사도. 1에 가까울수록 가깝다
	 */
	public record Neighbor(UUID bulletId, String text, String company, String period,
			double similarity) {
	}
}
