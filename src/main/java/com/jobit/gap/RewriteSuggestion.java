package com.jobit.gap;

import com.jobit.resume.ResumeBullet;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * WEAK 항목에 대한 문장 수정 제안. 스펙 §3.4.
 *
 * <p>{@code accepted}는 단순 플래그가 아니라 <b>품질 지표</b>다. 어떤 수정안이 실제로 채택되는지가
 * 프롬프트 개선의 근거가 된다 (스펙 §3.4).
 */
@Entity
@Table(name = "rewrite_suggestion")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RewriteSuggestion {

	@Id
	@GeneratedValue
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "gap_item_id", nullable = false)
	private GapItem gapItem;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "bullet_id", nullable = false)
	private ResumeBullet bullet;

	@Column(nullable = false)
	private String original;

	@Column(nullable = false)
	private String suggested;

	/** 왜 이렇게 고쳤는지 한 줄. 수정안 옆에 나란히 보여준다 (스펙 §4.5). */
	@Column(nullable = false)
	private String reason;

	@Column(nullable = false)
	private boolean accepted;

	public RewriteSuggestion(GapItem gapItem, ResumeBullet bullet, String original,
			String suggested, String reason) {
		this.gapItem = gapItem;
		this.bullet = bullet;
		this.original = original;
		this.suggested = suggested;
		this.reason = reason;
	}

	public void accept() {
		this.accepted = true;
	}

	public void reject() {
		this.accepted = false;
	}
}
