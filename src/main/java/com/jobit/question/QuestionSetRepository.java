package com.jobit.question;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionSetRepository extends JpaRepository<QuestionSet, UUID> {

	/** {@code promptVersion}이 같으면 재생성하지 않는다 (스펙 §4.2). */
	Optional<QuestionSet> findByJobPostingIdAndPromptVersion(UUID jobPostingId,
			String promptVersion);
}
