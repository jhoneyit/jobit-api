package com.jobit.gap;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GapAnalysisRepository extends JpaRepository<GapAnalysis, UUID> {

	/** 캐시 재사용 (스펙 §3.4). 같은 조합은 재분석하지 않는다. */
	Optional<GapAnalysis> findByResumeIdAndJobPostingId(UUID resumeId, UUID jobPostingId);
}
