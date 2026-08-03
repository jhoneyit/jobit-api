package com.jobit.gap;

import com.jobit.jd.Requirement;
import com.jobit.resume.ResumeBullet;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 요구사항 하나에 대한 판정. 스펙 §3.4.
 *
 * <p>{@code MISSING}은 근거가 없다는 뜻이며, 이 경우 이력서 문장을 <b>지어내지 않는다</b>.
 * "근거 없음"을 그대로 노출하고 질문 생성으로 넘긴다 (스펙 §4.5).
 */
@Entity
@Table(name = "gap_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GapItem {

	public enum Status {
		MET, WEAK, MISSING
	}

	@Id
	@GeneratedValue
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "gap_analysis_id", nullable = false)
	private GapAnalysis gapAnalysis;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "requirement_id", nullable = false)
	private Requirement requirement;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Status status;

	/** 근거가 된 이력서 문장. {@code MISSING}이면 null이다. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "evidence_bullet_id")
	private ResumeBullet evidenceBullet;

	/** 왜 이렇게 판정했는지. 사용자에게 그대로 보여준다. */
	@Column(nullable = false)
	private String rationale;

	public GapItem(GapAnalysis gapAnalysis, Requirement requirement, Status status,
			ResumeBullet evidenceBullet, String rationale) {
		this.gapAnalysis = gapAnalysis;
		this.requirement = requirement;
		this.status = status;
		this.evidenceBullet = evidenceBullet;
		this.rationale = rationale;
	}
}
