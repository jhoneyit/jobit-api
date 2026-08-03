package com.jobit.submission;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JdSubmissionRepository extends JpaRepository<JdSubmission, UUID> {

	/**
	 * 이력 목록 (스펙 §4.6). 최근순 정렬은 {@code idx_jd_submission_owner}가 받는다.
	 * 목록에서 회사·제목을 바로 쓰므로 공고를 함께 가져온다.
	 */
	@Query("""
			select s from JdSubmission s
			join fetch s.jobPosting
			where s.ownerKey = :ownerKey
			order by s.updatedAt desc
			""")
	Page<JdSubmission> findByOwner(@Param("ownerKey") String ownerKey, Pageable pageable);

	Optional<JdSubmission> findByOwnerKeyAndJobPostingId(String ownerKey, UUID jobPostingId);

	/** 상세 화면 진입 시 소유자 확인을 겸한다 — 남의 이력이 열리면 안 된다. */
	Optional<JdSubmission> findByIdAndOwnerKey(UUID id, String ownerKey);

	/**
	 * 승계 시 양쪽에 다 있는 공고의 익명 쪽 행을 지운다.
	 * {@code (owner_key, job_posting_id)} 유니크 제약에 걸리지 않도록 이동 전에 부른다.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			delete from JdSubmission s
			where s.ownerKey = :from
			  and s.jobPosting.id in (
			      select t.jobPosting.id from JdSubmission t where t.ownerKey = :to)
			""")
	int deleteCollisions(@Param("from") String fromOwnerKey, @Param("to") String toOwnerKey);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update JdSubmission s set s.ownerKey = :to where s.ownerKey = :from")
	int transferOwnership(@Param("from") String fromOwnerKey, @Param("to") String toOwnerKey);
}
