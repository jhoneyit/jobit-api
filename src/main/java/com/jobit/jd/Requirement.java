package com.jobit.jd;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 공고에서 뽑아낸 요구사항. 질문 생성과 갭 분석이 공유하는 앵커다 (스펙 §3).
 */
@Entity
@Table(name = "requirement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Requirement {

	public enum Kind {
		REQUIRED, PREFERRED, RESPONSIBILITY
	}

	@Id
	@GeneratedValue
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "job_posting_id", nullable = false)
	private JobPosting jobPosting;

	@Column(nullable = false)
	private String text;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Kind kind;

	/** 매칭·검색용. */
	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(nullable = false)
	private String[] keywords;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	public Requirement(JobPosting jobPosting, String text, Kind kind, String[] keywords,
			int sortOrder) {
		this.jobPosting = jobPosting;
		this.text = text;
		this.kind = kind;
		this.keywords = keywords;
		this.sortOrder = sortOrder;
	}
}
