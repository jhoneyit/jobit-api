package com.jobit.gap;

import com.jobit.jd.JobPosting;
import com.jobit.resume.Resume;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 이력서 × 공고 갭 분석 1회. 스펙 §3.4.
 *
 * <p>{@code (resume, jobPosting)} 유니크 제약이 곧 캐시 키다. 같은 조합은 재분석하지 않고
 * 기존 결과를 재사용한다 (스펙 §6 비용 통제).
 */
@Entity
@Table(name = "gap_analysis")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GapAnalysis {

	@Id
	@GeneratedValue
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "resume_id", nullable = false)
	private Resume resume;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "job_posting_id", nullable = false)
	private JobPosting jobPosting;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	public GapAnalysis(Resume resume, JobPosting jobPosting) {
		this.resume = resume;
		this.jobPosting = jobPosting;
	}
}
