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

	/**
	 * 답변 뼈대 (핵심 포인트 목록).
	 *
	 * <p><b>null 이 아니다.</b> V6 이 NOT NULL + {@code DEFAULT '[]'} 로 바꿨다 — 비어 있을 수는
	 * 있어도 없을 수는 없다. 선언에 {@code nullable} 이 빠져 있으면 매핑이 스키마보다 느슨해
	 * 보이는데, {@code ddl-auto=validate} 는 nullable 을 검사하지 않아 그 어긋남이 드러나지 않는다.
	 *
	 * <p>이 값은 면접 연습의 <b>채점 기준</b>이기도 하다 (docs/interview-practice-design.md).
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "answer_outline", nullable = false)
	private String answerOutline;

	/**
	 * 생성된 순서 = 표시 순서.
	 *
	 * <p>없으면 조회할 때마다 순서가 뒤바뀐다 — 모델이 난이도와 카테고리를 섞어 배치한 의도가
	 * 사라진다. V6 에서 컬럼을 추가했다.
	 */
	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	public Question(QuestionSet questionSet, Requirement requirement, String text,
			Category category, short difficulty, String followups, String answerOutline,
			int sortOrder) {
		this.questionSet = questionSet;
		this.requirement = requirement;
		this.text = text;
		this.category = category;
		this.difficulty = difficulty;
		this.followups = followups;
		this.answerOutline = answerOutline;
		this.sortOrder = sortOrder;
	}
}
