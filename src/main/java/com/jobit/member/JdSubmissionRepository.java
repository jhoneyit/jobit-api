package com.jobit.member;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JdSubmissionRepository extends JpaRepository<JdSubmission, UUID> {

	/**
	 * 이력 목록 (스펙 §4.6). 최근순 정렬은 {@code idx_jd_submission_member}가 받는다.
	 * 목록에서 회사·제목을 바로 쓰므로 공고를 함께 가져온다.
	 */
	@Query("""
			select s from JdSubmission s
			join fetch s.jobPosting
			where s.member.id = :memberId
			order by s.updatedAt desc
			""")
	Page<JdSubmission> findByMember(@Param("memberId") UUID memberId, Pageable pageable);

	Optional<JdSubmission> findByMemberIdAndJobPostingId(UUID memberId, UUID jobPostingId);

	/** 상세 화면 진입 시 소유자 확인을 겸한다 — 남의 이력이 열리면 안 된다. */
	Optional<JdSubmission> findByIdAndMemberId(UUID id, UUID memberId);
}
