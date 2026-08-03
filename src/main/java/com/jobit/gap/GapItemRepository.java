package com.jobit.gap;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GapItemRepository extends JpaRepository<GapItem, UUID> {

	/** 갭 분석 결과 화면. 요구사항 순서대로 보여준다 (스펙 §4.5). */
	@Query("""
			select i from GapItem i
			join fetch i.requirement r
			left join fetch i.evidenceBullet
			where i.gapAnalysis.id = :gapAnalysisId
			order by r.sortOrder
			""")
	List<GapItem> findForDisplay(@Param("gapAnalysisId") UUID gapAnalysisId);

	/**
	 * 이력 목록용 집계 (스펙 §4.6). 공고 목록을 한 번에 넘겨 N+1을 피한다.
	 *
	 * <p>상태별 CASE 대신 {@code group by status}로 세 행을 받아 Java에서 접는다 —
	 * JPQL의 중첩 enum 상수 표기가 Hibernate 버전을 타서, 확실히 동작하는 쪽을 택했다.
	 * 접는 것은 {@link GapSummary#fold}.
	 */
	@Query("""
			select a.jobPosting.id, i.status, count(i)
			from GapItem i
			join i.gapAnalysis a
			where a.resume.id = :resumeId and a.jobPosting.id in :jobPostingIds
			group by a.jobPosting.id, i.status
			""")
	List<Object[]> countByStatus(@Param("resumeId") UUID resumeId,
			@Param("jobPostingIds") Collection<UUID> jobPostingIds);
}
