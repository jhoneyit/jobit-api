package com.jobit.member;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

	/** 검증은 해시로 조회한다. 원문은 어디에도 저장되어 있지 않다. */
	@Query("""
			select t from PasswordResetToken t
			join fetch t.member
			where t.tokenHash = :tokenHash
			""")
	Optional<PasswordResetToken> findByTokenHash(@Param("tokenHash") String tokenHash);

	/** 재발급 시 무효화 대상. */
	List<PasswordResetToken> findByMemberIdAndUsedAtIsNull(UUID memberId);

	/** 60초 내 재요청 판정용 (스펙 §3.7). */
	Optional<PasswordResetToken> findFirstByMemberIdOrderByCreatedAtDesc(UUID memberId);

	/** 만료 토큰 정리. 배치에서 호출한다. */
	@Modifying
	@Query("delete from PasswordResetToken t where t.expiresAt < :threshold")
	int deleteExpiredBefore(@Param("threshold") OffsetDateTime threshold);
}
