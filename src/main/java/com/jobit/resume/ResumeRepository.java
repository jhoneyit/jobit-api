package com.jobit.resume;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResumeRepository extends JpaRepository<Resume, UUID> {

	List<Resume> findByOwnerKeyOrderByCreatedAtDesc(String ownerKey);

	Optional<Resume> findByIdAndOwnerKey(UUID id, String ownerKey);

	/**
	 * 익명 세션 → 회원 데이터 이관 (스펙 §3.6). 로그인 시 호출한다.
	 * 이관 트리거를 로그인 즉시로 할지 사용자 확인 후로 할지는 미정 (스펙 §7).
	 */
	@Modifying
	@Query("update Resume r set r.ownerKey = :memberOwnerKey where r.ownerKey = :anonymousKey")
	int transferOwnership(@Param("anonymousKey") String anonymousKey,
			@Param("memberOwnerKey") String memberOwnerKey);

	/**
	 * 보관 기간이 지난 이력서를 지운다 (스펙 §6 개인정보).
	 *
	 * <p><b>면접 답변과 달리 행을 통째로 지운다.</b> 그쪽은 원문만 비우고 점수·피드백을 남겼는데,
	 * 그건 "언제 무엇을 연습했는가"가 원문 없이도 사용자에게 쓸모 있는 기록이기 때문이다.
	 * 이력서는 문장 자체가 내용의 전부라 원문을 지우고 남길 것이 없다.
	 *
	 * <p>{@code resume_bullet} 과 {@code gap_analysis} 는 {@code on delete cascade} 로 함께
	 * 사라진다 — JPA 캐스케이드가 아니라 <b>DB 제약</b>이라 이 벌크 삭제에도 적용된다.
	 */
	@Modifying
	@Query("delete from Resume r where r.expiresAt is not null and r.expiresAt < :now")
	int deleteExpired(@Param("now") OffsetDateTime now);
}
