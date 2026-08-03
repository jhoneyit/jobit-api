package com.jobit.resume;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResumeBulletRepository extends JpaRepository<ResumeBullet, UUID> {

	List<ResumeBullet> findByResumeIdOrderBySortOrder(UUID resumeId);
}
