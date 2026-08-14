package com.jobit.gap;

import com.jobit.common.NotFoundException;
import com.jobit.jd.JobPosting;
import com.jobit.jd.JobPostingRepository;
import com.jobit.jd.Requirement;
import com.jobit.jd.RequirementRepository;
import com.jobit.llm.EmbeddingClient;
import com.jobit.llm.LlmException;
import com.jobit.llm.LlmGuard;
import com.jobit.resume.Resume;
import com.jobit.resume.ResumeBullet;
import com.jobit.resume.ResumeBulletEmbeddingRepository;
import com.jobit.resume.ResumeBulletRepository;
import com.jobit.resume.ResumeRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 갭 분석 흐름 (스펙 §4.3).
 *
 * <pre>
 * 1. 캐시 확인          — (resume, jobPosting) 이 곧 캐시 키. 적중이면 LLM 도 한도도 안 탄다
 * 2. 한도 확인          — GPU 시간이 나가기 직전
 * 3. 요구사항 임베딩     — 한 번의 배치 호출
 * 4. 후보 추림 (pgvector) — 요구사항마다 상위 3개 (스펙 §4.3 1단계)
 * 5. 판정 (LLM)          — 요구사항 수만큼. 느리다 — 요구사항 20개면 몇 분
 * 6. 저장               — 분석 1건 + 항목 전부를 한 트랜잭션에
 * </pre>
 *
 * <p><b>LLM 호출을 트랜잭션 밖에 둔다</b> ({@code ResumeService} 와 같은 이유). 판정이 요구사항
 * 수만큼 반복되어 분석 하나가 몇 분을 먹는데, 그동안 커넥션을 붙들면 동시 분석 몇 건으로 풀이
 * 마르고 무관한 기능까지 멈춘다.
 *
 * <p><b>한도는 분석 1건에 1회 소비한다.</b> 실제 LLM 호출은 요구사항 수만큼이지만, 면접 연습처럼
 * 별도 겹을 두지는 않았다 — 갭 분석은 캐시가 있어 같은 조합의 재분석이 공짜이고, 새 조합은
 * 사용자가 가진 이력서 × 공고 수로 자연히 묶인다. 판정이 직렬이라 분석 하나가 몇 분을 차지하는
 * 것 자체가 GPU 를 나눠 쓰는 셈이기도 하다. 남용이 관측되면 그때 면접 연습의 세 번째 겹을
 * 참고해 조인다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GapAnalysisService {

	/** 요구사항당 후보 수 (스펙 §4.3 1단계). 이 값이 "전수 LLM 호출 금지"를 성립시킨다. */
	static final int CANDIDATE_LIMIT = 3;

	private final GapAnalysisRepository gapAnalysisRepository;

	private final GapItemRepository gapItemRepository;

	private final ResumeRepository resumeRepository;

	private final ResumeBulletRepository bulletRepository;

	private final ResumeBulletEmbeddingRepository embeddingRepository;

	private final JobPostingRepository jobPostingRepository;

	private final RequirementRepository requirementRepository;

	private final GapJudge gapJudge;

	private final EmbeddingClient embeddingClient;

	private final LlmGuard llmGuard;

	private final TransactionTemplate transactionTemplate;

	/**
	 * 분석을 실행하거나, 이미 있으면 그 결과를 돌려준다.
	 *
	 * <p><b>같은 조합은 재분석하지 않는다</b> (스펙 §3.4). 이력서를 고쳤다면 새로 올린 이력서가
	 * 새 {@code resumeId} 를 받으므로 캐시 키가 자연히 갈린다 — 이력서에 수정 개념이 없는 것이
	 * 여기서 캐시 무효화 문제를 없애 준다.
	 */
	public Result analyze(String ownerKey, UUID resumeId, UUID jobPostingId) {
		Resume resume = resumeRepository.findByIdAndOwnerKey(resumeId, ownerKey)
			.orElseThrow(() -> new NotFoundException("resume not found: " + resumeId));
		JobPosting jobPosting = jobPostingRepository.findById(jobPostingId)
			.orElseThrow(() -> new NotFoundException("job posting not found: " + jobPostingId));

		Optional<GapAnalysis> cached = gapAnalysisRepository
			.findByResumeIdAndJobPostingId(resumeId, jobPostingId);
		if (cached.isPresent()) {
			return load(cached.get(), true);
		}

		List<Requirement> requirements = requirementRepository
			.findByJobPostingIdOrderBySortOrder(jobPostingId);
		if (requirements.isEmpty()) {
			// 파싱 재검증이 "요구사항 0개"를 캐시에 넣지 않으므로 정상 경로에서 올 수 없다.
			throw new IllegalStateException("job posting has no requirements: " + jobPostingId);
		}

		// V11 이 임베딩 컬럼을 갈아엎어 "문장은 있는데 벡터가 없는" 이력서가 실존한다.
		// 후보 없이 판정하면 전부 MISSING 이 되어 캐시에 굳는다 — 여기서 끊고 재업로드를 안내한다.
		if (embeddingRepository.countWithEmbedding(resumeId) == 0) {
			throw new ResumeNotAnalyzableException(resumeId);
		}

		// 캐시 적중은 위에서 이미 나갔다 — 실제 추론이 일어날 때만 소비한다 (LlmGuard 규약).
		llmGuard.checkAndConsume(ownerKey);

		List<Judged> judged = judgeAll(resumeId, requirements);

		try {
			return transactionTemplate
				.execute(status -> persist(resume, jobPosting, judged));
		}
		catch (DataIntegrityViolationException ex) {
			// 같은 조합을 동시에 분석한 경합 — 유니크 제약이 한쪽을 이겼다. 진 쪽은 이긴 쪽의
			// 결과를 돌려준다 (JdParsingService 와 같은 처리). 판정에 쓴 시간은 아깝지만
			// 결과는 같은 입력에서 나왔으므로 버려도 사용자가 잃는 것이 없다.
			log.info("갭 분석 경합: resume={} jobPosting={} — 먼저 저장된 결과를 재사용한다", resumeId,
					jobPostingId);
			GapAnalysis winner = gapAnalysisRepository
				.findByResumeIdAndJobPostingId(resumeId, jobPostingId)
				.orElseThrow(() -> new IllegalStateException(
						"gap analysis vanished after conflict: " + resumeId));
			return load(winner, true);
		}
	}

	/** 캐시된 결과 조회 전용. 없으면 404 — 분석을 시작하지 않는다 (그건 {@link #analyze} 의 일이다). */
	public Result getExisting(String ownerKey, UUID resumeId, UUID jobPostingId) {
		resumeRepository.findByIdAndOwnerKey(resumeId, ownerKey)
			.orElseThrow(() -> new NotFoundException("resume not found: " + resumeId));

		GapAnalysis analysis = gapAnalysisRepository
			.findByResumeIdAndJobPostingId(resumeId, jobPostingId)
			.orElseThrow(() -> new NotFoundException(
					"gap analysis not found: %s × %s".formatted(resumeId, jobPostingId)));
		return load(analysis, true);
	}

	private List<Judged> judgeAll(UUID resumeId, List<Requirement> requirements) {
		List<String> texts = new ArrayList<>(requirements.size());
		for (Requirement requirement : requirements) {
			texts.add(requirement.getText());
		}

		List<float[]> vectors = embeddingClient.embedAll(texts);
		if (vectors.size() != requirements.size()) {
			// 어긋난 채 진행하면 요구사항이 남의 벡터로 후보를 뽑는다 — 이력서 업로드와 같은 방어.
			throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
					"공고를 분석하지 못했습니다. 잠시 후 다시 시도해 주세요.");
		}

		List<Judged> judged = new ArrayList<>(requirements.size());
		for (int i = 0; i < requirements.size(); i++) {
			Requirement requirement = requirements.get(i);

			List<GapJudge.Candidate> candidates = new ArrayList<>(CANDIDATE_LIMIT);
			for (ResumeBulletEmbeddingRepository.Neighbor neighbor : embeddingRepository
				.findNearest(resumeId, vectors.get(i), CANDIDATE_LIMIT)) {
				candidates.add(new GapJudge.Candidate(neighbor.bulletId(), neighbor.text()));
			}

			GapJudge.Verdict verdict = gapJudge
				.judge(new GapJudge.Request(requirement.getText(), candidates));
			judged.add(new Judged(requirement, verdict));

			log.debug("갭 판정 {}/{}: {} → {}", i + 1, requirements.size(), requirement.getId(),
					verdict.status());
		}
		return judged;
	}

	private Result persist(Resume resume, JobPosting jobPosting, List<Judged> judged) {
		GapAnalysis analysis = gapAnalysisRepository.save(new GapAnalysis(resume, jobPosting));

		List<GapItem> items = new ArrayList<>(judged.size());
		for (Judged entry : judged) {
			ResumeBullet evidence = entry.verdict().evidenceBulletId() == null ? null
					: bulletRepository.getReferenceById(entry.verdict().evidenceBulletId());
			items.add(new GapItem(analysis, entry.requirement(), entry.verdict().status(), evidence,
					entry.verdict().rationale()));
		}
		gapItemRepository.saveAll(items);

		log.info("갭 분석 저장: resume={} jobPosting={} 항목={}개", resume.getId(), jobPosting.getId(),
				items.size());
		// 방금 만든 항목이 손에 있지만 findForDisplay 로 다시 읽는다 — evidence 가 지연 프록시라
		// 화면에 줄 문장 텍스트가 없고, 캐시 경로와 형태를 맞추면 두 경로가 한 코드로 검증된다.
		return load(analysis, false);
	}

	private Result load(GapAnalysis analysis, boolean cached) {
		return new Result(analysis, gapItemRepository.findForDisplay(analysis.getId()), cached);
	}

	/**
	 * @param cached true 면 LLM 을 부르지 않고 기존 결과를 돌려준 것이다. 프론트가 "방금 분석함"과
	 *               "이전 결과"를 구분해 보여줄 수 있게 한다
	 */
	public record Result(GapAnalysis analysis, List<GapItem> items, boolean cached) {
	}

	private record Judged(Requirement requirement, GapJudge.Verdict verdict) {
	}

	/**
	 * 벡터가 하나도 없는 이력서 — V11 이전에 올렸거나 임베딩 저장이 실패한 경우다.
	 * 사용자가 할 일이 분명하므로(다시 올린다) 문구가 그걸 가리켜야 한다.
	 */
	public static class ResumeNotAnalyzableException extends RuntimeException {

		public ResumeNotAnalyzableException(UUID resumeId) {
			super("resume has no embeddings: " + resumeId);
		}
	}
}
