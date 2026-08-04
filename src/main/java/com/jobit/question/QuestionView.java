package com.jobit.question;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.util.UUID;

/**
 * SSE로 내보내는 질문 하나.
 *
 * <p>엔티티를 그대로 직렬화하지 않는다 — 지연 로딩 프록시가 직렬화 시점에 터지고,
 * {@code questionSet} 을 타고 공고 원문까지 딸려 나간다.
 *
 * <p>{@code id} 가 null 일 수 있다: 스트리밍 중에는 아직 저장 전이라 DB가 id를 발급하지 않았다.
 * 화면은 순서(sortOrder)로 key를 잡으면 된다.
 */
public record QuestionView(UUID id, UUID requirementId, String text, Question.Category category,
		short difficulty,

		/** 이미 JSON 배열 문자열이므로 다시 이스케이프하지 않는다. */
		@JsonRawValue String followups,

		@JsonRawValue String answerOutline,

		int sortOrder) {

	static QuestionView of(Question q) {
		return new QuestionView(q.getId(),
				q.getRequirement() == null ? null : q.getRequirement().getId(), q.getText(),
				q.getCategory(), q.getDifficulty(), q.getFollowups(), q.getAnswerOutline(),
				q.getSortOrder());
	}
}
