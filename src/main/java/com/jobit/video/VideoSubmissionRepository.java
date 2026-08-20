package com.jobit.video;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VideoSubmissionRepository extends JpaRepository<VideoSubmission, UUID> {

	Optional<VideoSubmission> findByOwnerKeyAndSummaryId(String ownerKey, UUID summaryId);

	/** 내 목록 — 최근순. summary 를 함께 fetch 해 목록 N+1 을 피한다. */
	@Query("""
			select s from VideoSubmission s
			join fetch s.summary
			where s.ownerKey = :ownerKey
			order by s.createdAt desc
			""")
	List<VideoSubmission> findForList(@Param("ownerKey") String ownerKey);
}
