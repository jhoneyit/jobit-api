package com.jobit.video;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 영상 요약 1건 — {@code video_id} 기준 전역 캐시다 (V14, {@code job_posting} 과 같은 구조).
 *
 * <p><b>상태 기계가 곧 API 계약이다.</b> 처리가 수 분(자막)~수십 분(STT)이라 동기 응답이
 * 불가능하고, 프론트는 이 상태를 폴링한다: PENDING → RUNNING → DONE | FAILED.
 */
@Entity
@Table(name = "video_summary")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VideoSummary {

	public enum Status {
		PENDING, RUNNING, DONE, FAILED, REJECTED
	}

	public enum Source {
		CAPTION, STT
	}

	@Id
	@GeneratedValue
	private UUID id;

	@Column(name = "video_id", nullable = false, unique = true)
	private String videoId;

	@Column(nullable = false)
	private String url;

	private String title;

	private String channel;

	@Column(name = "duration_sec")
	private Integer durationSec;

	@Enumerated(EnumType.STRING)
	@Column(name = "transcript_source", length = 10)
	private Source transcriptSource;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Status status;

	/** 사용자에게 그대로 보여줄 실패 문구. 내부 예외 메시지를 넣지 않는다. */
	@Column(name = "error_message")
	private String errorMessage;

	/** {@code VideoReportResponse} 직렬화. DONE 일 때만 있다. */
	@JdbcTypeCode(SqlTypes.JSON)
	private String report;

	@Column(name = "prompt_version")
	private String promptVersion;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	public VideoSummary(String videoId, String url, OffsetDateTime now) {
		this.videoId = videoId;
		this.url = url;
		this.status = Status.PENDING;
		this.updatedAt = now;
	}

	public void start(OffsetDateTime now) {
		this.status = Status.RUNNING;
		this.errorMessage = null;
		this.updatedAt = now;
	}

	public void meta(String title, String channel, Integer durationSec, OffsetDateTime now) {
		this.title = title;
		this.channel = channel;
		this.durationSec = durationSec;
		this.updatedAt = now;
	}

	public void complete(Source source, String reportJson, String promptVersion,
			OffsetDateTime now) {
		this.transcriptSource = source;
		this.report = reportJson;
		this.promptVersion = promptVersion;
		this.status = Status.DONE;
		this.errorMessage = null;
		this.updatedAt = now;
	}

	public void fail(String userMessage, OffsetDateTime now) {
		this.status = Status.FAILED;
		this.errorMessage = userMessage;
		this.updatedAt = now;
	}

	/** 주제 게이트 거부 — 실패가 아니라 "대상 아님"이다. 문구·다음 행동이 달라 상태를 가른다. */
	public void reject(String userMessage, OffsetDateTime now) {
		this.status = Status.REJECTED;
		this.errorMessage = userMessage;
		this.updatedAt = now;
	}

	/** 재시도 진입 — FAILED·REJECTED, 그리고 프롬프트가 낡은 DONE(재요약)이 여기로 돌아온다. */
	public void requeue(OffsetDateTime now) {
		this.status = Status.PENDING;
		this.errorMessage = null;
		this.updatedAt = now;
	}
}
