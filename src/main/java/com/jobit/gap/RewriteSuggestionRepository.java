package com.jobit.gap;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RewriteSuggestionRepository extends JpaRepository<RewriteSuggestion, UUID> {

	List<RewriteSuggestion> findByGapItemIdIn(List<UUID> gapItemIds);

	/** 제안 캐시 (V12 유니크가 "항목당 하나"를 보장한다). */
	Optional<RewriteSuggestion> findByGapItemId(UUID gapItemId);

	/** 채택/철회 진입용 — 소유자 검사가 조회 조건에 들어간다 (docs/api.md "소유자 검사"). */
	@Query("""
			select s from RewriteSuggestion s
			join s.gapItem i
			join i.gapAnalysis a
			join a.resume r
			where s.id = :suggestionId and r.ownerKey = :ownerKey
			""")
	Optional<RewriteSuggestion> findOwned(@Param("suggestionId") UUID suggestionId,
			@Param("ownerKey") String ownerKey);
}
