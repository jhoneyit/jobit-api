package com.jobit.jd;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequirementRepository extends JpaRepository<Requirement, UUID> {

	List<Requirement> findByJobPostingIdOrderBySortOrder(UUID jobPostingId);
}
