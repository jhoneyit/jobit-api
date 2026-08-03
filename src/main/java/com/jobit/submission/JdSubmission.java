package com.jobit.submission;

import com.jobit.common.OwnerKey;
import com.jobit.jd.JobPosting;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * JD 입력 이력. 스펙 §3.6.
 *
 * <p>{@code job_posting}은 {@code content_hash} 기준 전역 캐시라 소유자를 붙일 수 없다 —
 * 붙이는 순간 같은 공고가 사람 수만큼 복제되어 캐시가 무너진다 (스펙 §4.1). 그래서 공고(공유
 * 자산)와 입력 이력(개인 자산)을 분리하고, 소유 관계는 이 테이블이 담는다.
 *
 * <p>소유자는 회원이 아니라 {@link OwnerKey}다. 비로그인 사용자도 이력을 갖기 때문이다 —
 * 익명으로 써보고 마음에 들어 가입하는 흐름에서 방금 만든 기록이 사라지면 안 된다.
 *
 * <p>같은 공고를 다시 넣으면 행을 새로 만들지 않고 {@link #touch()}로 {@code updatedAt}만
 * 갱신한다. 목록 중복은 막지만 "몇 번 봤는지"는 남지 않는다.
 */
@Entity
@Table(name = "jd_submission")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JdSubmission {

	@Id
	@GeneratedValue
	private UUID id;

	/**
	 * {@code user:<user_id>} 또는 {@code anon:<세션 쿠키>} ({@link OwnerKey}).
	 *
	 * <p>익명 → 계정 승계에서 값이 바뀌므로 {@code updatable = false}를 걸지 않는다.
	 */
	@Column(name = "owner_key", nullable = false)
	private String ownerKey;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "job_posting_id", nullable = false)
	private JobPosting jobPosting;

	/** "지원 완료", "1차 탈락" 등 사용자 메모. 목록에서 공고를 구분하기 위한 최소 장치다. */
	private String memo;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	public JdSubmission(String ownerKey, JobPosting jobPosting) {
		this.ownerKey = OwnerKey.requireValid(ownerKey);
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
