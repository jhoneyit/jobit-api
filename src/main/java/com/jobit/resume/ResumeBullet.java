package com.jobit.resume;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 이력서를 문장 단위로 쪼갠 항목. 스펙 §3.3.
 *
 * <p>전체를 LLM에 보내면 토큰 낭비이고 원치 않는 부분까지 바뀌므로, 리라이트는 항상 이 단위로 한다.
 *
 * <p><b>{@code embedding vector(1536)} 컬럼은 이 엔티티에 매핑하지 않는다.</b> JPA 표준 타입이
 * 아니고, 실제 사용처가 코사인 유사도 상위 N개 조회(스펙 §4.3 1단계)라 네이티브 쿼리로 다루는 편이
 * 현실적이다. {@code ResumeBulletEmbeddingRepository} 참고.
 */
@Entity
@Table(name = "resume_bullet")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResumeBullet {

	@Id
	@GeneratedValue
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "resume_id", nullable = false)
	private Resume resume;

	private String company;

	private String period;

	@Column(nullable = false)
	private String text;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	public ResumeBullet(Resume resume, String company, String period, String text, int sortOrder) {
		this.resume = resume;
		this.company = company;
		this.period = period;
		this.text = text;
		this.sortOrder = sortOrder;
	}
}
