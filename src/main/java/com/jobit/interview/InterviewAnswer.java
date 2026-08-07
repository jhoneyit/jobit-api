package com.jobit.interview;

import com.jobit.question.Question;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 문항 하나에 대한 답변과 채점 결과 (docs/interview-practice-design.md).
 *
 * <p><b>오디오는 저장하지 않는다.</b> STT 는 브라우저(Web Speech API)가 하고 서버는
 * {@code transcript}(텍스트)만 받는다. 그래서 오디오 컬럼도, 그에 딸린 암호화·삭제 경로도 없다.
 *
 * <p>{@code transcript}에는 사용자가 자기 경력을 말한 내용이 그대로 들어간다. 이력서에 준해
 * 다룬다 (스펙 §6) — <b>로그에 남기지 않는다.</b> 그래서 {@code toString}을 만들지 않는다
 * ({@code Resume}과 같은 이유).
 */
@Entity
@Table(name = "interview_answer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterviewAnswer {

	@Id
	@GeneratedValue
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "session_id", nullable = false)
	private InterviewSession session;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "question_id", nullable = false)
	private Question question;

	/**
	 * 출제 순서 = 표시 순서.
	 *
	 * <p>지금은 {@code question.sortOrder}와 같지만 값을 따로 남긴다 — 나중에 재연습에서
	 * 문항을 섞게 되면 둘이 갈라진다.
	 */
	@Column(name = "sort_order", nullable = false)
	private short sortOrder;

	/** STT 결과. <b>null 은 "시간 내에 답하지 못했다"는 정상 경로다</b> — 그 자체가 결과다. */
	@Column(name = "transcript")
	private String transcript;

	@Column(name = "duration_ms", nullable = false)
	private int durationMs;

	/**
	 * 그때의 제한 시간.
	 *
	 * <p>설정을 바꿔도 과거 기록의 의미가 변하면 안 된다 — 설정값을 참조만 하면 과거 점수의
	 * 근거가 소급해서 바뀐다.
	 */
	@Column(name = "time_limit_sec", nullable = false)
	private short timeLimitSec;

	/** 0~100. 채점 전이면 null. */
	@Column(name = "score")
	private Short score;

	/**
	 * 짚은 포인트 — {@code question.answerOutline}의 <b>인덱스 배열</b>이다 (예: {@code [0,2]}).
	 *
	 * <p>뼈대 문구를 복사해 두지 않는 이유: 같은 문장이 두 군데 살게 되고, 화면은 어차피 뼈대를
	 * 나란히 보여줘야 한다. 모델이 범위 밖 인덱스를 지어낼 수 있으므로 저장 전에 재검증한다.
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "covered")
	private String covered;

	/** 놓친 포인트. {@link #covered}와 같은 규약. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "missed")
	private String missed;

	/**
	 * 한 줄 피드백.
	 *
	 * <p><b>모범 답변을 대신 써주지 않는다</b> (스펙 §4.5 "지어내지 않는다"의 적용). 답을 써 주면
	 * 다음 연습에서 그걸 외워 말하게 되고 점수만 오른다.
	 */
	@Column(name = "feedback")
	private String feedback;

	@Column(name = "scored_at")
	private OffsetDateTime scoredAt;

	/**
	 * {@code transcript}만의 TTL.
	 *
	 * <p>만료되면 {@code transcript}만 지우고 점수·{@code covered}/{@code missed}·피드백은
	 * 남긴다. 기록 전체를 지우면 "내 면접 기록" 기능이 죽고, 발화 원문 없이도 점수 추이는 읽힌다.
	 */
	@Column(name = "transcript_expires_at")
	private OffsetDateTime transcriptExpiresAt;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	public InterviewAnswer(InterviewSession session, Question question, int sortOrder,
			String transcript, int durationMs, int timeLimitSec,
			OffsetDateTime transcriptExpiresAt) {
		this.session = session;
		this.question = question;
		this.sortOrder = (short) sortOrder;
		this.durationMs = durationMs;
		this.timeLimitSec = (short) timeLimitSec;
		this.transcriptExpiresAt = transcriptExpiresAt;
		replaceTranscript(transcript);
	}

	/** 답했는가. 공백만 말한 경우도 답하지 않은 것으로 본다. */
	public boolean answered() {
		return transcript != null;
	}

	public boolean scored() {
		return score != null;
	}

	/**
	 * 총점 계산에 쓸 점수.
	 *
	 * <p><b>미답변·미채점은 0이다.</b> 답한 것만 평균 내면 한 문항만 답하고 나가는 쪽이
	 * 유리해진다 (설계 문서 §4).
	 */
	public int scoreOrZero() {
		return score == null ? 0 : score;
	}

	/**
	 * 다시 제출한 답변으로 갈아끼운다 — 마이크가 안 잡혔을 때의 재시도 경로다.
	 *
	 * <p>행을 새로 만들지 않는 이유는 {@code (session_id, question_id)} 유니크 제약 때문이며,
	 * 그 제약을 둔 이유는 같은 질문의 점수가 여러 개 남으면 총점이 흔들리기 때문이다.
	 * <b>이전 채점 결과를 함께 지운다</b> — 답이 바뀌었는데 점수만 남으면 둘이 어긋난다.
	 */
	void resubmit(String transcript, int durationMs, int timeLimitSec,
			OffsetDateTime transcriptExpiresAt) {
		replaceTranscript(transcript);
		this.durationMs = durationMs;
		this.timeLimitSec = (short) timeLimitSec;
		this.transcriptExpiresAt = transcriptExpiresAt;
		this.score = null;
		this.covered = null;
		this.missed = null;
		this.feedback = null;
		this.scoredAt = null;
	}

	/**
	 * 채점 결과를 붙인다.
	 *
	 * @param covered {@code question.answerOutline} 인덱스의 JSON 배열 문자열.
	 *                범위 검증은 호출부(채점 서비스)가 이미 끝낸 상태여야 한다
	 */
	void applyScore(int score, String covered, String missed, String feedback,
			OffsetDateTime at) {
		if (score < 0 || score > 100) {
			throw new IllegalArgumentException("score must be 0..100: " + score);
		}
		this.score = (short) score;
		this.covered = covered;
		this.missed = missed;
		this.feedback = feedback;
		this.scoredAt = at;
	}

	/** TTL 만료 시 발화 원문만 지운다. 점수와 피드백은 남는다. */
	void forgetTranscript() {
		this.transcript = null;
	}

	/** 공백만 들어온 것은 답하지 않은 것으로 본다 — 빈 문자열을 채점에 태우면 안 된다. */
	private void replaceTranscript(String transcript) {
		this.transcript = (transcript == null || transcript.isBlank()) ? null : transcript.strip();
	}
}
