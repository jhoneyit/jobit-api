package com.jobit.video;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * QnA 검색용 자막 청크 (V16). JPA 를 쓰지 않는 이유는 다른 임베딩 리포지토리들과 같다 —
 * 이 테이블의 사용처가 적재와 유사도 검색 둘뿐이다.
 */
@Repository
@RequiredArgsConstructor
public class VideoChunkRepository {

	private final JdbcClient jdbc;

	public void saveAll(UUID summaryId, List<Chunk> chunks) {
		for (int i = 0; i < chunks.size(); i++) {
			Chunk chunk = chunks.get(i);
			jdbc.sql("""
					insert into video_chunk (video_summary_id, start_sec, content, embedding, sort_order)
					values (:summaryId, :startSec, :content, cast(:embedding as vector), :sortOrder)
					""")
				.param("summaryId", summaryId)
				.param("startSec", chunk.startSec())
				.param("content", chunk.content())
				.param("embedding", RequirementEmbeddingRepositoryBridge
					.toVectorLiteral(chunk.embedding()))
				.param("sortOrder", i)
				.update();
		}
	}

	/** 재요약(FAILED→PENDING 재처리) 시 이전 적재를 비운다 — 중복 적재는 검색을 오염시킨다. */
	public void deleteBySummaryId(UUID summaryId) {
		jdbc.sql("delete from video_chunk where video_summary_id = :summaryId")
			.param("summaryId", summaryId)
			.update();
	}

	public boolean existsForSummary(UUID summaryId) {
		return jdbc.sql("select count(*) from video_chunk where video_summary_id = :summaryId")
			.param("summaryId", summaryId)
			.query(Integer.class)
			.single() > 0;
	}

	/** 질문 벡터와 가까운 청크 상위 N — QnA 프롬프트의 근거 발췌다. */
	public List<Retrieved> findNearest(UUID summaryId, float[] query, int limit) {
		return jdbc.sql("""
				select start_sec, content, 1 - (embedding <=> cast(:query as vector)) as similarity
				from video_chunk
				where video_summary_id = :summaryId and embedding is not null
				order by embedding <=> cast(:query as vector)
				limit :limit
				""")
			.param("query", RequirementEmbeddingRepositoryBridge.toVectorLiteral(query))
			.param("summaryId", summaryId)
			.param("limit", limit)
			.query((rs, rowNum) -> new Retrieved(rs.getInt("start_sec"), rs.getString("content"),
					rs.getDouble("similarity")))
			.list();
	}

	public record Chunk(int startSec, String content, float[] embedding) {
	}

	public record Retrieved(int startSec, String content, double similarity) {
	}
}
