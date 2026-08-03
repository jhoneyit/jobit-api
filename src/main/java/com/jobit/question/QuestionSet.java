package com.jobit.question;

import com.jobit.jd.JobPosting;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 한 번의 질문 생성 결과 묶음. 스펙 §3.2.
 *
 * <p>{@code promptVersion}이 같으면 재생성하지 않는다 (스펙 §4.2).
 */
@Entity
@Table(name = "question_set")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuestionSet {

	@Id
	@GeneratedValue
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "job_posting_id", nullable = false)
	private JobPosting jobPosting;

	/** 프롬프트가 바뀌면 재생성 판단용. */
	@Column(name = "prompt_version", nullable = false)
	private String promptVersion;

	@Column(nullable = false)
	private String model;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	public QuestionSet(JobPosting jobPosting, String promptVersion, String model) {
		this.jobPosting = jobPosting;
		this.promptVersion = promptVersion;
		this.model = model;
	}
}
