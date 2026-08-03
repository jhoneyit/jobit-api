package com.jobit.resume;

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
}
