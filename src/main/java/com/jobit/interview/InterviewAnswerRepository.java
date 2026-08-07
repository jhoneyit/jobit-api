package com.jobit.interview;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InterviewAnswerRepository extends JpaRepository<InterviewAnswer, UUID> {

	/**
	 * 세션 상세 화면. 질문을 함께 가져온다 — 화면이 답변 옆에 질문과 답변 뼈대를 나란히
	 * 보여줘야 {@code covered}/{@code missed} 인덱스가 의미를 갖는다.
	 */
	@Query("""
			select a from InterviewAnswer a
			join fetch a.question
			where a.session.id = :sessionId
			order by a.sortOrder
			""")
	List<InterviewAnswer> findForDisplay(@Param("sessionId") UUID sessionId);

	/**
	 * 재제출 판정용. 있으면 새 행을 만들지 않고 갈아끼운다 —
	 * {@code (session_id, question_id)} 유니크 제약이 있다.
	 */
	Optional<InterviewAnswer> findBySessionIdAndQuestionId(UUID sessionId, UUID questionId);

	/**
	 * 총점 계산용. {@code findForDisplay}와 달리 질문을 붙이지 않는다 — 점수만 필요하다.
	 */
	List<InterviewAnswer> findBySessionIdOrderBySortOrder(UUID sessionId);

	/**
	 * 세션 삭제 시 답변을 먼저 지운다.
	 *
	 * <p>DB에 {@code ON DELETE CASCADE}가 있으므로 이것 없이도 행은 지워진다. 그런데도 명시적으로
	 * 부르는 이유는 <b>JPA가 그 cascade 를 모르기 때문</b>이다 — 같은 트랜잭션에서 답변을 이미
	 * 읽어 둔 상태로 세션만 지우면, 영속성 컨텍스트에 남은 답변이 사라진 세션을 참조해
	 * flush 에서 {@code TransientPropertyValueException} 이 난다.
	 *
	 * <p>지금 운영 경로({@code InterviewService.delete})는 답변을 읽지 않아 문제가 드러나지
	 * 않지만, 읽는 경로가 하나만 생기면 바로 터진다. DB cascade 는 안전망으로 남겨 둔다.
	 */
	void deleteBySessionId(UUID sessionId);

	/**
	 * 만료된 발화 원문을 지운다 (개인정보 TTL).
	 *
	 * <p><b>행을 지우지 않고 {@code transcript}만 null 로 만든다.</b> 기록 전체를 지우면
	 * "내 면접 기록" 기능이 죽고, 발화 원문 없이도 점수 추이는 그대로 읽힌다.
	 *
	 * <p>{@code transcript is not null} 조건이 있어야 이미 지운 행을 매번 다시 훑지 않는다 —
	 * {@code idx_interview_answer_transcript_expiry}가 그 조건의 부분 인덱스다.
	 *
	 * @return 지운 행 수
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update InterviewAnswer a set a.transcript = null
			where a.transcript is not null
			  and a.transcriptExpiresAt is not null
			  and a.transcriptExpiresAt < :now
			""")
	int forgetExpiredTranscripts(@Param("now") OffsetDateTime now);
}
