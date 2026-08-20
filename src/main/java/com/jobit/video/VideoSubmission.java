package com.jobit.video;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 소유자별 요약 이력 ({@code jd_submission} 과 같은 역할 — 전역 캐시와 내 목록을 잇는다). */
@Entity
@Table(name = "video_submission")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VideoSubmission {

	@Id
	@GeneratedValue
	private UUID id;

	@Column(name = "owner_key", nullable = false)
	private String ownerKey;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "video_summary_id", nullable = false)
	private VideoSummary summary;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	public VideoSubmission(String ownerKey, VideoSummary summary) {
		this.ownerKey = ownerKey;
		this.summary = summary;
	}
}
