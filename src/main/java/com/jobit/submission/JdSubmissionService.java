package com.jobit.submission;

import com.jobit.common.OwnerKey;
import com.jobit.gap.GapItemRepository;
import com.jobit.gap.GapSummary;
import com.jobit.jd.JobPosting;
import com.jobit.resume.Resume;
import com.jobit.resume.ResumeRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * JD 입력 이력 (스펙 §3.6, §4.6).
 *
 * <p>소유자는 회원이 아니라 {@link OwnerKey}다 — 비로그인 사용자도 이력을 갖는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JdSubmissionService {

	private final JdSubmissionRepository submissionRepository;

	private final GapItemRepository gapItemRepository;

	private final ResumeRepository resumeRepository;

	/**
	 * 공고를 넣었음을 기록한다.
	 *
	 * <p>같은 공고를 다시 넣으면 행을 새로 만들지 않고 {@code updatedAt}만 갱신한다 (스펙 §3.6).
	 */
	@Transactional
	public JdSubmission record(String ownerKey, JobPosting jobPosting) {
		OwnerKey.requireValid(ownerKey);
		return submissionRepository
			.findByOwnerKeyAndJobPostingId(ownerKey, jobPosting.getId())
			.map(existing -> {
				existing.touch();
				return existing;
			})
			.orElseGet(() -> submissionRepository.save(new JdSubmission(ownerKey, jobPosting)));
	}

	@Transactional
	public void updateMemo(UUID submissionId, String ownerKey, String memo) {
		JdSubmission submission = submissionRepository.findByIdAndOwnerKey(submissionId, ownerKey)
			.orElseThrow(() -> new IllegalArgumentException("submission not found: " + submissionId));
		submission.changeMemo(memo);
	}

	/**
	 * 이력 목록 (스펙 §4.6). 각 줄에 갭 요약 한 줄을 붙인다.
	 *
	 * <p>요약 기준은 <b>가장 최근 이력서</b>다. 이력서를 여러 개 둘 수 있으므로 어느 것을
	 * 기준으로 볼지는 제품 판단이며, 목록에서 이력서를 고르게 하는 것은 과하다고 봤다.
	 * 이력서가 없으면 요약 없이 목록만 반환한다.
	 *
	 * <p>집계는 페이지에 실린 공고 ID를 한 번에 넘겨 한 방에 가져온다 — 줄마다 조회하면 N+1이다.
	 */
	@Transactional(readOnly = true)
	public Page<SubmissionListItem> list(String ownerKey, Pageable pageable) {
		OwnerKey.requireValid(ownerKey);

		Page<JdSubmission> page = submissionRepository.findByOwner(ownerKey, pageable);
		if (page.isEmpty()) {
			return page.map(submission -> toItem(submission, Map.of()));
		}

		Map<UUID, GapSummary> summaries = summariesFor(ownerKey, page.getContent());
		return page.map(submission -> toItem(submission, summaries));
	}

	private Map<UUID, GapSummary> summariesFor(String ownerKey, List<JdSubmission> submissions) {
		List<Resume> resumes = resumeRepository.findByOwnerKeyOrderByCreatedAtDesc(ownerKey);
		if (resumes.isEmpty()) {
			return Map.of();
		}

		List<UUID> jobPostingIds = submissions.stream()
			.map(submission -> submission.getJobPosting().getId())
			.toList();
		return GapSummary.fold(
				gapItemRepository.countByStatus(resumes.getFirst().getId(), jobPostingIds));
	}

	private SubmissionListItem toItem(JdSubmission submission, Map<UUID, GapSummary> summaries) {
		JobPosting posting = submission.getJobPosting();
		return new SubmissionListItem(submission.getId(), posting.getId(), posting.getCompany(),
				posting.getTitle(), submission.getMemo(), submission.getUpdatedAt(),
				summaries.get(posting.getId()));
	}

	/** 상세 화면. 소유자가 아니면 비어 있다 — 남의 이력이 열려선 안 된다. */
	@Transactional(readOnly = true)
	public JdSubmission getOwned(UUID submissionId, String ownerKey) {
		return submissionRepository.findByIdAndOwnerKey(submissionId, ownerKey)
			.orElseThrow(() -> new IllegalArgumentException("submission not found: " + submissionId));
	}

	/**
	 * 익명으로 쌓은 이력을 계정으로 승계한다 (스펙 §3.6).
	 *
	 * <p>이게 없으면 "질문 만들어 보고 마음에 들어서 로그인했더니 방금 만든 게 사라진" 상태가 된다.
	 *
	 * <p><b>충돌 행을 먼저 지운다.</b> 익명일 때와 로그인 후에 같은 공고를 넣었으면 두 행이
	 * 생기는데, 그대로 소유자만 바꾸면 {@code (owner_key, job_posting_id)} 유니크 제약에 걸려
	 * 승계 전체가 실패한다. 계정 쪽 행이 이미 있으므로 익명 쪽을 버리는 것이 맞다.
	 *
	 * @return 옮겨진 이력 수
	 */
	@Transactional
	public int transferOwnership(String fromOwnerKey, String toOwnerKey) {
		OwnerKey.requireValid(fromOwnerKey);
		OwnerKey.requireValid(toOwnerKey);
		if (fromOwnerKey.equals(toOwnerKey)) {
			return 0;
		}

		int dropped = submissionRepository.deleteCollisions(fromOwnerKey, toOwnerKey);
		int moved = submissionRepository.transferOwnership(fromOwnerKey, toOwnerKey);
		if (moved > 0 || dropped > 0) {
			log.info("Transferred {} submission(s) to {} ({} duplicate(s) dropped)", moved,
					toOwnerKey, dropped);
		}
		return moved;
	}
}
