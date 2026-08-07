package com.jobit.interview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobit.common.NotFoundException;
import com.jobit.common.OwnerKey;
import com.jobit.jd.JobPosting;
import com.jobit.jd.JobPostingRepository;
import com.jobit.llm.LlmGuard;
import com.jobit.question.Question;
import com.jobit.question.QuestionGenPrompts;
import com.jobit.question.QuestionRepository;
import com.jobit.question.QuestionSet;
import com.jobit.question.QuestionSetRepository;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 면접 연습 세션 (docs/interview-practice-design.md §4).
 *
 * <p><b>지출 방어가 두 층이다.</b> 세션 시작에서 일별 세션 수를 막고, 답변 채점마다
 * {@link LlmGuard}를 부른다. 후자만으로는 이 기능이 다른 기능의 한도를 잡아먹고, 전자만으로는
 * 전역 상한이 걸리지 않는다.
 */
@Service
@Slf4j
public class InterviewService {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final InterviewSessionRepository sessionRepository;

	private final InterviewAnswerRepository answerRepository;

	private final JobPostingRepository jobPostingRepository;

	private final QuestionSetRepository questionSetRepository;

	private final QuestionRepository questionRepository;

	private final AnswerScorer scorer;

	private final LlmGuard llmGuard;

	private final int questionsPerSession;

	private final int sessionsPerDay;

	private final int timeLimitSec;

	private final int transcriptTtlDays;

	public InterviewService(InterviewSessionRepository sessionRepository,
			InterviewAnswerRepository answerRepository, JobPostingRepository jobPostingRepository,
			QuestionSetRepository questionSetRepository, QuestionRepository questionRepository,
			AnswerScorer scorer, LlmGuard llmGuard,
			@Value("${jobit.interview.questions-per-session:5}") int questionsPerSession,
			@Value("${jobit.interview.sessions-per-day:6}") int sessionsPerDay,
			@Value("${jobit.interview.time-limit-sec:90}") int timeLimitSec,
			@Value("${jobit.interview.transcript-ttl-days:90}") int transcriptTtlDays) {
		this.sessionRepository = sessionRepository;
		this.answerRepository = answerRepository;
		this.jobPostingRepository = jobPostingRepository;
		this.questionSetRepository = questionSetRepository;
		this.questionRepository = questionRepository;
		this.scorer = scorer;
		this.llmGuard = llmGuard;
		this.questionsPerSession = questionsPerSession;
		this.sessionsPerDay = sessionsPerDay;
		this.timeLimitSec = timeLimitSec;
		this.transcriptTtlDays = transcriptTtlDays;
		log.info("면접 연습: 세션당 {}문항, 소유자당 하루 {}세션, 제한 {}초", questionsPerSession,
				sessionsPerDay, timeLimitSec);
	}

	/**
	 * 세션을 시작한다.
	 *
	 * <p><b>질문이 이미 만들어진 공고에서만 시작할 수 있다.</b> 제약이 아니라 진입점이다 —
	 * 채점 기준({@code answer_outline})이 질문 생성의 산물이므로, 질문이 없으면 채점할 기준도 없다.
	 *
	 * <p>출제는 {@code sortOrder} 앞에서부터다. 모델이 난이도·카테고리를 섞어 배치한 순서가
	 * 이미 있어 따로 고르지 않는다 (설계 문서 §3).
	 */
	@Transactional
	public StartedSession start(String ownerKey, UUID jobPostingId) {
		OwnerKey.requireValid(ownerKey);
		checkDailySessionLimit(ownerKey);

		JobPosting posting = jobPostingRepository.findById(jobPostingId)
			.orElseThrow(() -> new NotFoundException("job posting not found: " + jobPostingId));

		QuestionSet questionSet = questionSetRepository
			.findByJobPostingIdAndPromptVersion(jobPostingId, QuestionGenPrompts.PROMPT_VERSION)
			.orElseThrow(() -> new QuestionsNotReadyException(jobPostingId));

		List<Question> questions = scorableQuestions(questionSet);
		if (questions.isEmpty()) {
			throw new QuestionsNotReadyException(jobPostingId);
		}

		InterviewSession session = sessionRepository
			.save(new InterviewSession(ownerKey, posting, questionSet, questions.size()));

		return new StartedSession(session, toPrompts(questions));
	}

	/**
	 * 채점할 수 있는 질문만 앞에서부터 고른다.
	 *
	 * <p><b>뼈대가 없는 질문은 건너뛴다.</b> 채점 기준이 없으면 점수를 매길 수 없고, 그런 문항을
	 * 세션에 넣으면 총점의 분모에는 들어가면서 절대 점수를 얻지 못해 총점이 부당하게 깎인다.
	 * 뼈대는 질문 생성 LLM 의 산물이라 비어 있는 경우가 실제로 생긴다.
	 */
	private List<Question> scorableQuestions(QuestionSet questionSet) {
		List<Question> picked = new ArrayList<>();
		for (Question question : questionRepository.findForDisplay(questionSet.getId())) {
			if (picked.size() >= questionsPerSession) {
				break;
			}
			if (!AnswerOutlines.parse(question.getAnswerOutline()).isEmpty()) {
				picked.add(question);
			}
		}
		return picked;
	}

	/**
	 * 일별 세션 상한.
	 *
	 * <p><b>고정 창이라 자정 경계에서 최대 두 배가 통과할 수 있다</b> — {@code LlmGuard}의
	 * 시간당 창과 같은 성질이고, 같은 이유로 허용한다. 총액은 전역 일일 상한이 막는다.
	 *
	 * <p>동시 요청 둘이 같은 값을 읽어 한도를 살짝 넘길 수도 있다. 카운터를 원자적으로 증가시키는
	 * {@code LlmGuard}와 달리 여기서는 세션 행 자체를 세기 때문인데, 넘어가 봐야 한두 개이고
	 * 실제 지출은 채점마다 걸리는 {@code LlmGuard}가 다시 막는다.
	 */
	private void checkDailySessionLimit(String ownerKey) {
		if (sessionsPerDay <= 0) {
			return; // 0 이면 끈다 (로컬 개발용)
		}

		OffsetDateTime since = OffsetDateTime.now().truncatedTo(ChronoUnit.DAYS);
		long today = sessionRepository.countByOwnerKeyAndStartedAtGreaterThanEqual(ownerKey, since);
		if (today >= sessionsPerDay) {
			throw new DailySessionLimitExceededException(sessionsPerDay);
		}
	}

	/**
	 * 답변을 제출하고 즉시 채점한다.
	 *
	 * <p><b>왜 즉시 채점하는가.</b> 답하고 나서 바로 무엇을 놓쳤는지 봐야 다음 문항에서 고쳐
	 * 말한다. 끝에 몰아 보여주면 채점표지 연습이 아니다 (설계 문서 §4). 몰아서 한 번 부르는 쪽이
	 * 싸지만 그러면 기능의 목적이 사라진다.
	 *
	 * <p>같은 질문에 다시 제출하면 행을 갈아끼운다 — 마이크가 안 잡혔을 때의 재시도 경로다.
	 */
	@Transactional
	public ScoredAnswer submitAnswer(UUID sessionId, String ownerKey, UUID questionId,
			String transcript, int durationMs) {

		InterviewSession session = getOwned(sessionId, ownerKey);
		if (session.isFinished()) {
			throw new IllegalArgumentException("session already finished: " + sessionId);
		}

		Question question = questionOf(session, questionId);
		List<String> outline = AnswerOutlines.parse(question.getAnswerOutline());
		if (outline.isEmpty()) {
			// start()가 걸렀어야 하는 상태다. 여기까지 왔다면 그 사이 질문이 바뀐 것이다.
			throw new IllegalArgumentException("question has no answer outline: " + questionId);
		}

		AnswerScorer.Score score = scoreOrSkip(question, outline, transcript, ownerKey);

		InterviewAnswer answer = answerRepository
			.findBySessionIdAndQuestionId(sessionId, questionId)
			.map(existing -> {
				existing.resubmit(transcript, durationMs, timeLimitSec, transcriptExpiry());
				return existing;
			})
			.orElseGet(() -> {
				// **새 답변일 때만 센다.** 재제출은 행을 덮어쓰므로 세면 answered_count 가
				// question_count 를 넘어 CHECK 제약에 걸린다.
				session.recordAnswered();
				return answerRepository.save(new InterviewAnswer(session, question,
						question.getSortOrder(), transcript, durationMs, timeLimitSec,
						transcriptExpiry()));
			});

		answer.applyScore(score.score(), toJson(score.covered()), toJson(score.missed()),
				score.feedback(), OffsetDateTime.now());

		return new ScoredAnswer(answer, outline, score);
	}

	/**
	 * 채점한다. 단, <b>답하지 않았으면 LLM 도 한도도 건드리지 않는다.</b>
	 *
	 * <p>제한 시간이 있는 이상 무응답은 드문 일이 아니라 정상 경로다. 여기서 한도를 소비하면
	 * 마이크가 안 잡힌 사용자가 자기 한도를 스스로 태우게 된다.
	 *
	 * <p>{@code LlmGuard}는 <b>돈이 나가기 직전 한 지점</b>에서 부른다는 기존 원칙을 그대로
	 * 따른다 — 지금 그 지점이 세 곳째다 (JD 파싱, 질문 생성, 답변 채점).
	 */
	private AnswerScorer.Score scoreOrSkip(Question question, List<String> outline,
			String transcript, String ownerKey) {
		if (transcript == null || transcript.isBlank()) {
			return AnswerScoreNormalizer.unanswered(outline.size());
		}

		llmGuard.checkAndConsume(ownerKey);

		AnswerScorer.Score raw = scorer.score(new AnswerScorer.Request(question.getText(), outline,
				question.getRequirement() == null ? null : question.getRequirement().getText(),
				transcript));

		// **구현을 믿지 않고 저장 직전에 한 번 더 정규화한다.** AnthropicAnswerScorer 도 같은
		// 일을 하지만, 그건 그 구현의 사정이다 — 포트 뒤에 무엇이 꽂히든 DB 에 들어가는 값은
		// 성질을 지켜야 한다 (점수 0~100 은 CHECK 제약이, 인덱스 범위는 아무것도 막지 않는다).
		// 멱등이라 두 번 걸어도 결과가 같다.
		return AnswerScoreNormalizer.normalize(raw.score(), raw.covered(), outline.size(),
				raw.feedback());
	}

	/**
	 * 세션을 닫고 총점을 확정한다.
	 *
	 * <p><b>총점은 출제된 전 문항의 평균이다.</b> 답한 것만 평균 내면 한 문항만 답하고 나가는
	 * 쪽이 유리해진다 — 미답변은 0점으로 센다 (설계 문서 §4).
	 *
	 * <p>이미 닫힌 세션에 다시 불러도 그대로 돌려준다. 마지막 문항 제출과 종료가 겹치거나
	 * 사용자가 새로고침하는 것은 정상 경로라 오류로 만들 이유가 없다.
	 */
	@Transactional
	public InterviewSession finish(UUID sessionId, String ownerKey) {
		InterviewSession session = getOwned(sessionId, ownerKey);
		if (session.isFinished()) {
			return session;
		}

		int sum = 0;
		for (InterviewAnswer answer : answerRepository
			.findBySessionIdOrderBySortOrder(sessionId)) {
			sum += answer.scoreOrZero();
		}

		session.finish(sum / session.getQuestionCount(), OffsetDateTime.now());
		return session;
	}

	/** 상세·삭제 진입점. 남의 세션은 존재 여부도 알려주지 않는다 (docs/api.md "소유자 검사"). */
	@Transactional(readOnly = true)
	public InterviewSession getOwned(UUID sessionId, String ownerKey) {
		OwnerKey.requireValid(ownerKey);
		return sessionRepository.findByIdAndOwnerKey(sessionId, ownerKey)
			.orElseThrow(() -> new NotFoundException("interview session not found: " + sessionId));
	}

	/**
	 * 면접 기록 목록 ({@code /profile/interviews}).
	 *
	 * <p>제출 이력과 달리 <b>집계를 붙이지 않는다.</b> 총점·답변 수가 이미 세션 행에 있어서
	 * 한 번의 조회로 끝난다 — {@code JdSubmissionService.list}가 세 종류의 집계를 배치로
	 * 모으는 것과 대조적이다. 점수를 세션에 저장해 둔 값이 여기서 값을 한다.
	 */
	@Transactional(readOnly = true)
	public Page<InterviewSession> list(String ownerKey, Pageable pageable) {
		OwnerKey.requireValid(ownerKey);
		return sessionRepository.findByOwner(ownerKey, pageable);
	}

	/**
	 * 세션 상세 — 문항별 점수와 뼈대 대조.
	 *
	 * <p>{@code transcript}는 <b>null 일 수 있다</b>. 답하지 못했거나, TTL 이 지나 발화 원문만
	 * 지워진 경우다 (설계 문서 §7). 둘을 {@code answered}로 구분한다.
	 */
	@Transactional(readOnly = true)
	public SessionDetail detail(UUID sessionId, String ownerKey) {
		InterviewSession session = getOwned(sessionId, ownerKey);

		List<AnsweredQuestion> answers = new ArrayList<>();
		for (InterviewAnswer answer : answerRepository.findForDisplay(sessionId)) {
			answers.add(new AnsweredQuestion(answer,
					AnswerOutlines.parse(answer.getQuestion().getAnswerOutline()),
					toIndexes(answer.getCovered()), toIndexes(answer.getMissed())));
		}

		return new SessionDetail(session, toPrompts(sessionQuestions(session)), answers);
	}

	/**
	 * 이 세션에 출제된 문항들.
	 *
	 * <p><b>왜 상세에 함께 싣는가.</b> 연습 화면이 새로고침을 견디려면 진행 중인 세션의 질문
	 * 목록을 다시 받을 수 있어야 한다. 시작 응답에만 있으면 새 탭에서 열거나 새로고침하는
	 * 순간 세션이 미아가 된다.
	 *
	 * <p><b>여기에는 답변 뼈대가 없다</b> ({@link QuestionPrompt}). 뼈대는 채점된 답변
	 * ({@link AnsweredQuestion})에만 실리므로, 아직 답하지 않은 문항의 기준이 새어 나가지 않는다.
	 *
	 * <p>{@code start()}와 같은 규칙으로 다시 고른다 — 세션의 질문 세트에서 채점 가능한 것을
	 * {@code questionCount}개까지. 세션에 그 수를 박아 두었으므로 설정이 바뀌어도 흔들리지 않는다.
	 */
	private List<Question> sessionQuestions(InterviewSession session) {
		List<Question> picked = new ArrayList<>();
		for (Question question : questionRepository
			.findForDisplay(session.getQuestionSet().getId())) {
			if (picked.size() >= session.getQuestionCount()) {
				break;
			}
			if (!AnswerOutlines.parse(question.getAnswerOutline()).isEmpty()) {
				picked.add(question);
			}
		}
		return picked;
	}

	/**
	 * 기록을 지운다.
	 *
	 * <p>답변을 <b>명시적으로 먼저 지운다.</b> DB에 {@code ON DELETE CASCADE}가 있어 없어도
	 * 행은 사라지지만, JPA는 그 cascade 를 모른다 — 같은 트랜잭션에서 답변을 읽어 둔 채로 세션만
	 * 지우면 영속성 컨텍스트에 남은 답변이 사라진 세션을 참조해 flush 에서 터진다.
	 *
	 * <p><b>질문과 공고는 건드리지 않는다</b>: 둘 다 공유 자산이고, 특히 공고는
	 * {@code content_hash} 전역 캐시라 지우면 남의 캐시 적중까지 깨진다.
	 */
	@Transactional
	public void delete(UUID sessionId, String ownerKey) {
		InterviewSession session = getOwned(sessionId, ownerKey);
		answerRepository.deleteBySessionId(sessionId);
		sessionRepository.delete(session);
	}

	/**
	 * 익명으로 쌓은 연습 기록을 계정으로 승계한다.
	 *
	 * <p>없으면 "연습해 보고 마음에 들어 로그인했더니 방금 본 점수가 사라진" 상태가 된다 —
	 * 제출 이력({@code JdSubmissionService.transferOwnership})과 같은 문제다.
	 *
	 * <p><b>다만 충돌 처리가 필요 없다.</b> 그쪽은 {@code (owner_key, job_posting_id)} 유니크
	 * 제약이 있어 양쪽에 같은 공고가 있으면 걸리지만, 세션은 같은 공고로 몇 번이든 연습할 수
	 * 있어 제약 자체가 없다.
	 *
	 * @return 옮겨진 세션 수
	 */
	@Transactional
	public int transferOwnership(String fromOwnerKey, String toOwnerKey) {
		OwnerKey.requireValid(fromOwnerKey);
		OwnerKey.requireValid(toOwnerKey);
		if (fromOwnerKey.equals(toOwnerKey)) {
			return 0;
		}

		int moved = sessionRepository.transferOwnership(fromOwnerKey, toOwnerKey);
		if (moved > 0) {
			log.info("면접 기록 {}건을 {} 로 옮겼습니다", moved, toOwnerKey);
		}
		return moved;
	}

	/** jsonb 에 저장된 인덱스 배열을 되읽는다. {@link #toJson}의 짝이다. */
	private List<Integer> toIndexes(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			return MAPPER.readValue(json, new TypeReference<List<Integer>>() {
			});
		}
		catch (JsonProcessingException ex) {
			// 우리가 쓴 값이라 여기 올 일이 없지만, 한 줄 때문에 상세 화면이 통째로 죽는
			// 것보다 그 줄만 비는 편이 낫다.
			log.warn("채점 인덱스를 읽지 못했습니다", ex);
			return List.of();
		}
	}

	/**
	 * 이 세션에 출제된 질문인지 확인한다.
	 *
	 * <p>세션의 질문 세트에 속하지 않는 질문 id 를 받으면 거절한다 — 없으면 남의 공고 질문에
	 * 답한 점수를 내 기록에 심을 수 있다.
	 */
	private Question questionOf(InterviewSession session, UUID questionId) {
		return questionRepository.findForDisplay(session.getQuestionSet().getId())
			.stream()
			.filter(question -> question.getId().equals(questionId))
			.findFirst()
			.orElseThrow(() -> new NotFoundException(
					"question not in session: " + questionId));
	}

	private OffsetDateTime transcriptExpiry() {
		return transcriptTtlDays <= 0 ? null : OffsetDateTime.now().plusDays(transcriptTtlDays);
	}

	/** {@code covered}/{@code missed}는 jsonb 컬럼이라 문자열로 직렬화해 넣는다. */
	private String toJson(List<Integer> indexes) {
		try {
			return MAPPER.writeValueAsString(indexes);
		}
		catch (JsonProcessingException ex) {
			throw new IllegalStateException("채점 결과를 직렬화하지 못했습니다", ex);
		}
	}

	private List<QuestionPrompt> toPrompts(List<Question> questions) {
		List<QuestionPrompt> prompts = new ArrayList<>(questions.size());
		for (Question question : questions) {
			prompts.add(new QuestionPrompt(question.getId(), question.getText(),
					question.getCategory(), question.getDifficulty(), timeLimitSec));
		}
		return prompts;
	}

	/**
	 * 세션 시작 응답.
	 *
	 * <p><b>답변 뼈대를 담지 않는다.</b> 보고 답하면 연습이 아니다 — 뼈대는 채점 후에 나온다.
	 */
	public record StartedSession(InterviewSession session, List<QuestionPrompt> questions) {
	}

	public record QuestionPrompt(UUID questionId, String text, Question.Category category,
			short difficulty, int timeLimitSec) {
	}

	/** @param outline 채점 후에야 공개되는 답변 뼈대. {@code covered}/{@code missed}가 가리키는 대상 */
	public record ScoredAnswer(InterviewAnswer answer, List<String> outline,
			AnswerScorer.Score score) {
	}

	/**
	 * @param questions 이 세션에 출제된 문항. <b>뼈대가 없다</b> — 아직 답하지 않은 문항의
	 *                  채점 기준이 새어 나가면 안 된다. 연습 화면이 새로고침 후 이어서 하려면
	 *                  이 목록이 필요하다
	 */
	public record SessionDetail(InterviewSession session, List<QuestionPrompt> questions,
			List<AnsweredQuestion> answers) {
	}

	/**
	 * 상세 화면의 한 줄 — 문항, 그때 한 답변, 뼈대 대조.
	 *
	 * @param outline 질문의 <b>현재</b> 답변 뼈대다. {@code question_set}을 세션에 박아 두었으므로
	 *                프롬프트 버전이 올라가도 이 질문 자체는 그대로다
	 */
	public record AnsweredQuestion(InterviewAnswer answer, List<String> outline,
			List<Integer> covered, List<Integer> missed) {
	}

	/** 질문이 아직 없는 공고. 사용자는 질문 생성으로 먼저 가야 한다. */
	public static class QuestionsNotReadyException extends RuntimeException {

		public QuestionsNotReadyException(UUID jobPostingId) {
			super("questions not generated for job posting: " + jobPostingId);
		}
	}

	/** 소유자별 일별 세션 상한 초과. */
	public static class DailySessionLimitExceededException extends RuntimeException {

		public DailySessionLimitExceededException(int limit) {
			super("daily interview session limit reached: " + limit);
		}
	}
}
