package com.jobit.interview;

import com.jobit.common.OwnerKey;
import com.jobit.jd.JobPosting;
import com.jobit.question.QuestionSet;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 한 번의 면접 연습 (docs/interview-practice-design.md).
 *
 * <p>소유자는 회원이 아니라 {@link OwnerKey}다 — 비로그인 사용자도 연습하고 기록을 갖는다
 * ({@code jd_submission}과 같은 규약).
 *
 * <p><b>{@code questionSet}을 세션에 박아 두는 이유.</b> {@code promptVersion}이 올라가면 같은
 * 공고라도 질문이 바뀌는데, 기록에는 "그때 그 질문"이 남아야 한다. {@code jobPosting}만
 * 들고 있으면 나중에 상세 화면을 열 때 다른 질문이 딸려 온다.
 */
@Entity
@Table(name = "interview_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterviewSession {

	@Id
	@GeneratedValue
	private UUID id;

	/** {@code user:<user_id>} 또는 {@code anon:<세션 쿠키>} ({@link OwnerKey}). */
	@Column(name = "owner_key", nullable = false)
	private String ownerKey;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "job_posting_id", nullable = false)
	private JobPosting jobPosting;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "question_set_id", nullable = false)
	private QuestionSet questionSet;

	/**
	 * 출제된 문항 수 = 총점의 분모.
	 *
	 * <p>설정({@code questions-per-session})을 참조만 하면 값을 바꿨을 때 과거 총점의 근거가
	 * 소급해서 바뀐다. 그래서 세션마다 값을 남긴다.
	 */
	@Column(name = "question_count", nullable = false)
	private short questionCount;

	@Column(name = "answered_count", nullable = false)
	private short answeredCount;

	/** 0~100. 종료 전이면 null — "아직 못 푼 것"과 "풀었는데 0점"은 다르다. */
	@Column(name = "total_score")
	private Short totalScore;

	@Column(name = "started_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime startedAt;

	/** null이면 진행 중이거나 중간에 이탈했다. 둘을 구분하지 않는다 — 어느 쪽이든 미완이다. */
	@Column(name = "finished_at")
	private OffsetDateTime finishedAt;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	public InterviewSession(String ownerKey, JobPosting jobPosting, QuestionSet questionSet,
			int questionCount) {
		if (questionCount <= 0) {
			throw new IllegalArgumentException("questionCount must be positive");
		}
		this.ownerKey = OwnerKey.requireValid(ownerKey);
		this.jobPosting = jobPosting;
		this.questionSet = questionSet;
		this.questionCount = (short) questionCount;
		this.answeredCount = 0;
	}

	public boolean isFinished() {
		return finishedAt != null;
	}

	/**
	 * 답변 하나가 채점되었음을 반영한다.
	 *
	 * <p>같은 질문에 다시 답하면(마이크가 안 잡혔을 때) 답변 행은 덮어쓰이므로 여기서 세지
	 * 않는다 — 그래서 호출부가 <b>새 답변일 때만</b> 부른다. 세면 answered_count 가
	 * question_count 를 넘어 CHECK 제약에 걸린다.
	 */
	void recordAnswered() {
		if (answeredCount >= questionCount) {
			throw new IllegalStateException(
					"answered_count cannot exceed question_count: " + questionCount);
		}
		this.answeredCount++;
	}

	/**
	 * 세션을 닫는다.
	 *
	 * @param totalScore 출제된 <b>전</b> 문항의 평균. 답한 것만 평균 내면 한 문항만 답하고
	 *                   나가는 쪽이 유리해지므로, 미답변은 0점으로 센 값이 들어와야 한다
	 */
	void finish(int totalScore, OffsetDateTime at) {
		if (totalScore < 0 || totalScore > 100) {
			throw new IllegalArgumentException("totalScore must be 0..100: " + totalScore);
		}
		this.totalScore = (short) totalScore;
		this.finishedAt = at;
	}
}
