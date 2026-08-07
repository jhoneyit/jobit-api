package com.jobit.interview;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InterviewSessionRepository extends JpaRepository<InterviewSession, UUID> {

	/**
	 * 면접 기록 목록 ({@code /profile/interviews}). 최근순 정렬은
	 * {@code idx_interview_session_owner}가 받는다.
	 *
	 * <p>목록에서 회사·제목을 바로 쓰므로 공고를 함께 가져온다 —
	 * {@code JdSubmissionRepository.findByOwner}와 같은 이유다.
	 */
	@Query("""
			select s from InterviewSession s
			join fetch s.jobPosting
			where s.ownerKey = :ownerKey
			order by s.startedAt desc
			""")
	Page<InterviewSession> findByOwner(@Param("ownerKey") String ownerKey, Pageable pageable);

	/**
	 * 오늘 이 소유자가 시작한 세션 수 (일별 상한 판정용).
	 *
	 * <p><b>별도 카운터 테이블을 두지 않는다.</b> {@code rate_limit_bucket}은 창을 2시간 뒤
	 * 정리하므로(`RateLimitCleanup`) 일별 창을 거기 두면 새벽 2시에 카운터가 사라진다.
	 * 세션 자체가 이미 기록이라 세면 되고, 그러면 드리프트도 정리 작업도 없다.
	 *
	 * <p>LLM 호출 카운터와 성격이 다르다는 점도 맞아떨어진다 — 그쪽은 트랜잭션이 롤백돼도
	 * <b>돈이 이미 나갔으므로</b> 소비가 남아야 하지만, 여기는 롤백돼서 세션이 없으면
	 * 세지 않는 것이 옳다.
	 */
	long countByOwnerKeyAndStartedAtGreaterThanEqual(String ownerKey, OffsetDateTime since);

	/**
	 * 상세·삭제 진입 시 소유자 확인을 겸한다.
	 *
	 * <p>소유자를 따로 비교하지 않고 조회 조건에 넣는다 — 남의 기록은 존재 여부도 알려주지
	 * 않는다 (docs/api.md "소유자 검사").
	 */
	Optional<InterviewSession> findByIdAndOwnerKey(UUID id, String ownerKey);

	/**
	 * 익명으로 쌓은 연습 기록을 계정으로 승계한다 (제출 이력과 같은 흐름).
	 *
	 * <p>{@code jd_submission}과 달리 <b>충돌 처리가 필요 없다</b>. 그쪽은
	 * {@code (owner_key, job_posting_id)} 유니크 제약이 있어 양쪽에 같은 공고가 있으면 걸리지만,
	 * 세션은 같은 공고로 몇 번이든 연습할 수 있어 유니크 제약 자체가 없다.
	 *
	 * @return 옮겨진 세션 수
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update InterviewSession s set s.ownerKey = :to where s.ownerKey = :from")
	int transferOwnership(@Param("from") String fromOwnerKey, @Param("to") String toOwnerKey);
}
