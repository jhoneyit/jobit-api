package com.jobit.gap;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RewriteSuggestionRepository extends JpaRepository<RewriteSuggestion, UUID> {

	List<RewriteSuggestion> findByGapItemIdIn(List<UUID> gapItemIds);
}
