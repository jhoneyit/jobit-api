package com.jobit.jd;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobPostingRepository extends JpaRepository<JobPosting, UUID> {

	/** 캐시 조회 (스펙 §4.1). 있으면 LLM 파싱을 건너뛴다. */
	Optional<JobPosting> findByContentHash(String contentHash);
}
