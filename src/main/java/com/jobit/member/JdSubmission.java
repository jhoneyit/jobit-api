package com.jobit.member;

import com.jobit.jd.JobPosting;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원의 JD 입력 이력. 스펙 §3.6.
 *
 * <p>{@code job_posting}은 전역 캐시라 회원을 직접 붙이지 않으므로, 소유 관계를 이 테이블이 담는다.
 * 같은 공고를 다시 넣으면 행을 새로 만들지 않고 {@link #touch()}로 {@code updatedAt}만 갱신한다.
 */
@Entity
@Table(name = "jd_submission")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JdSubmission {

	@Id
	@GeneratedValue
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "job_posting_id", nullable = false)
	private JobPosting jobPosting;

	/** "지원 완료", "1차 탈락" 등 사용자 메모. 목록에서 공고를 구분하기 위한 최소 장치다. */
	private String memo;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	public JdSubmission(Member member, JobPosting jobPosting) {
		this.member = member;
		this.jobPosting = jobPosting;
		this.updatedAt = OffsetDateTime.now();
	}

	public void touch() {
		this.updatedAt = OffsetDateTime.now();
	}

	public void changeMemo(String memo) {
		this.memo = memo;
		touch();
	}
}
