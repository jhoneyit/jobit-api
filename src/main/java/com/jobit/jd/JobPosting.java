package com.jobit.jd;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 채용공고. {@code contentHash} 기준 전역 캐시이므로 소유자를 붙이지 않는다 (스펙 §3.6).
 * 회원과의 관계는 {@code jd_submission}이 담당한다.
 */
@Entity
@Table(name = "job_posting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobPosting {

	@Id
	@GeneratedValue
	private UUID id;

	/** 정규화한 본문의 해시. 캐시 키 (스펙 §4.1). */
	@Column(name = "content_hash", nullable = false, unique = true)
	private String contentHash;

	@Column(name = "raw_text", nullable = false)
	private String rawText;

	@Column(name = "source_url")
	private String sourceUrl;

	private String company;

	private String title;

	/**
	 * 스택, 연차, 도메인 등 추출 결과.
	 *
	 * <p>비워 둘 수 없다. {@code contentHash} 캐시는 한 번 저장된 행을 계속 재사용한다는 뜻이라,
	 * 파싱이 빠진 채 저장되면 그 상태로 굳는다 (V5 마이그레이션).
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private String parsed;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	public JobPosting(String contentHash, String rawText, String sourceUrl, String company,
			String title, String parsed) {
		this.contentHash = contentHash;
		this.rawText = rawText;
		this.sourceUrl = sourceUrl;
		this.company = company;
		this.title = title;
		this.parsed = parsed;
	}
}
