package com.jobit.resume;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 이력서 응답 DTO (docs/api.md).
 *
 * <p><b>어느 응답에도 이력서 원문({@code raw_text})이 없다.</b> 실수로 빠뜨린 것이 아니라 규약이다 —
 * 원문은 암호화해 저장하고, 화면이 필요로 하는 것은 분해된 문장 목록이다. 원문을 내려 주는 순간
 * 암호화는 "DB 를 직접 본 사람만 막는" 장치로 격하되고, 응답 로그·프록시 캐시·브라우저 히스토리에
 * 평문이 남는다. 복호화 경로는 문장 단위 리라이트(스펙 §4.4)가 들어올 때 <b>서버 안에서만</b>
 * 열린다.
 */
public final class ResumeView {

	private ResumeView() {
	}

	/**
	 * 업로드 결과.
	 *
	 * <p>{@code bulletCount} 를 내려 주는 이유는 화면이 "몇 문장으로 나뉘었는지"를 즉시 보여
	 * 주어야 하기 때문이다. 분해 결과가 기대와 다르면(1문장으로 뭉쳤다거나) 사용자가 바로
	 * 알아채고 다시 올릴 수 있다.
	 */
	public record Uploaded(UUID resumeId, int bulletCount, OffsetDateTime expiresAt) {

		public static Uploaded of(ResumeService.Stored stored) {
			return new Uploaded(stored.resume().getId(), stored.bulletCount(),
					stored.resume().getExpiresAt());
		}
	}

	/** 목록 한 줄. */
	public record Summary(UUID resumeId, int bulletCount, OffsetDateTime createdAt,
			OffsetDateTime expiresAt) {
	}

	public record ListResponse(List<Summary> items) {
	}

	/**
	 * 상세.
	 *
	 * @param embeddedCount 벡터가 채워진 문장 수. 정상이면 {@code bullets.size()} 와 같다.
	 *                      값을 노출하는 이유는 <b>갭 분석 가능 여부가 이 값에 달려 있기</b>
	 *                      때문이다 — 0이면 유사도 검색이 후보를 하나도 못 찾는다
	 */
	public record Detail(UUID resumeId, int embeddedCount, OffsetDateTime createdAt,
			OffsetDateTime expiresAt, List<Bullet> bullets) {

		public static Detail of(ResumeService.Detail detail) {
			List<Bullet> bullets = new ArrayList<>(detail.bullets().size());
			for (ResumeBullet bullet : detail.bullets()) {
				bullets.add(new Bullet(bullet.getId(), bullet.getText(), bullet.getCompany(),
						bullet.getPeriod(), bullet.getSortOrder()));
			}
			Resume resume = detail.resume();
			return new Detail(resume.getId(), detail.embeddedCount(), resume.getCreatedAt(),
					resume.getExpiresAt(), bullets);
		}
	}

	public record Bullet(UUID bulletId, String text, String company, String period, int sortOrder) {
	}
}
