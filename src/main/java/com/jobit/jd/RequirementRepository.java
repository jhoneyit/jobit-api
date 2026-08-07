package com.jobit.jd;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RequirementRepository extends JpaRepository<Requirement, UUID> {

	List<Requirement> findByJobPostingIdOrderBySortOrder(UUID jobPostingId);

	/**
	 * 이력 목록에 붙일 요구사항 개수. 페이지에 실린 공고 ID를 한 번에 넘긴다 —
	 * 줄마다 세면 N+1이다.
	 *
	 * <p>요구사항이 없는 공고는 결과에 아예 나오지 않는다 ({@code group by}라 0행은 없다).
	 * 호출부에서 0으로 채운다.
	 *
	 * @return {@code (jobPostingId, count)} 행들. 접는 것은 {@code JdSubmissionService}
	 */
	@Query("""
			select r.jobPosting.id, count(r)
			from Requirement r
			where r.jobPosting.id in :jobPostingIds
			group by r.jobPosting.id
			""")
	List<Object[]> countByJobPosting(@Param("jobPostingIds") Collection<UUID> jobPostingIds);
}
