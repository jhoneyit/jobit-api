package com.jobit.jd;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/jd/parse} 응답 (docs/api.md).
 *
 * <p>엔티티를 그대로 직렬화하지 않는다 — {@code rawText}(공고 원문 전체)가 응답에 실려 나가고,
 * 지연 로딩 프록시가 직렬화 시점에 터진다.
 */
public record JdParseResult(UUID jobPostingId, String company, String title,

		/** 이미 JSON 문자열이므로 다시 이스케이프하지 않고 그대로 내보낸다. */
		@JsonRawValue String parsed,

		boolean cached, List<RequirementView> requirements) {

	public record RequirementView(UUID id, String text, Requirement.Kind kind,
			List<String> keywords, int sortOrder) {

		static RequirementView of(Requirement requirement) {
			return new RequirementView(requirement.getId(), requirement.getText(),
					requirement.getKind(), List.of(requirement.getKeywords()),
					requirement.getSortOrder());
		}
	}

	static JdParseResult of(JobPosting posting, List<Requirement> requirements, boolean cached) {
		return new JdParseResult(posting.getId(), posting.getCompany(), posting.getTitle(),
				posting.getParsed(), cached,
				requirements.stream().map(RequirementView::of).toList());
	}
}
