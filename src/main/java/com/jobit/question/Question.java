package com.jobit.question;

import com.jobit.jd.Requirement;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 면접 예상 질문. 스펙 §3.2.
 *
 * <p>{@code requirement}가 null일 수 있는 이유: 특정 요구사항에서 파생되지 않은
 * 일반 CS·컬처핏 질문도 같은 묶음에 담기기 때문이다.
 */
@Entity
@Table(name = "question")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Question {

	public enum Category {
		CS, STACK, EXPERIENCE, DESIGN, CULTURE
	}

	@Id
	@GeneratedValue
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "question_set_id", nullable = false)
	private QuestionSet questionSet;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "requirement_id")
	private Requirement requirement;

	@Column(nullable = false)
	private String text;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Category category;

	@Column(nullable = false)
	private short difficulty;

	/** 꼬리질문 배열. */
	@JdbcTypeCode(SqlTypes.JSON)
	private String followups;

	/** 답변 뼈대 (핵심 포인트 목록). */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "answer_outline")
	private String answerOutline;

	public Question(QuestionSet questionSet, Requirement requirement, String text,
			Category category, short difficulty, String followups, String answerOutline) {
		this.questionSet = questionSet;
		this.requirement = requirement;
		this.text = text;
		this.category = category;
		this.difficulty = difficulty;
		this.followups = followups;
		this.answerOutline = answerOutline;
	}
}
