package com.jobit.member;

import com.jobit.gap.GapItemRepository;
import com.jobit.gap.GapSummary;
import com.jobit.jd.JobPosting;
import com.jobit.resume.Resume;
import com.jobit.resume.ResumeRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * JD 입력 이력 (스펙 §3.6, §4.6).
 */
@Service
@RequiredArgsConstructor
public class JdSubmissionService {

	private final JdSubmissionRepository submissionRepository;

	private final GapItemRepository gapItemRepository;

	private final ResumeRepository resumeRepository;

	/**
	 * 회원이 공고를 넣었음을 기록한다.
	 *
	 * <p>같은 공고를 다시 넣으면 행을 새로 만들지 않고 {@code updatedAt}만 갱신한다 (스펙 §3.6).
	 * 목록 중복은 막지만 "몇 번 봤는지"는 남지 않는다.
	 */
	@Transactional
	public JdSubmission record(Member member, JobPosting jobPosting) {
		return submissionRepository
			.findByMemberIdAndJobPostingId(member.getId(), jobPosting.getId())
			.map(existing -> {
				existing.touch();
				return existing;
			})
			.orElseGet(() -> submissionRepository.save(new JdSubmission(member, jobPosting)));
	}

	@Transactional
	public void updateMemo(UUID submissionId, UUID memberId, String memo) {
		JdSubmission submission = submissionRepository.findByIdAndMemberId(submissionId, memberId)
			.orElseThrow(() -> new IllegalArgumentException("submission not found: " + submissionId));
		submission.changeMemo(memo);
	}

	/**
	 * 이력 목록 (스펙 §4.6). 각 줄에 갭 요약 한 줄을 붙인다.
	 *
	 * <p>요약 기준은 <b>회원의 가장 최근 이력서</b>다. 이력서를 여러 개 둘 수 있으므로 어느 것을
	 * 기준으로 볼지는 제품 판단이며, 목록에서 이력서를 고르게 하는 것은 과하다고 봤다.
	 * 이력서가 없으면 요약 없이 목록만 반환한다.
	 *
	 * <p>집계는 페이지에 실린 공고 ID를 한 번에 넘겨 한 방에 가져온다 — 줄마다 조회하면 N+1이다.
	 */
	@Transactional(readOnly = true)
	public Page<SubmissionListItem> list(Member member, Pageable pageable) {
		Page<JdSubmission> page = submissionRepository.findByMember(member.getId(), pageable);
		if (page.isEmpty()) {
			return page.map(submission -> toItem(submission, Map.of()));
		}

		Map<UUID, GapSummary> summaries = summariesFor(member, page.getContent());
		return page.map(submission -> toItem(submission, summaries));
	}

	private Map<UUID, GapSummary> summariesFor(Member member, List<JdSubmission> submissions) {
		List<Resume> resumes = resumeRepository
			.findByOwnerKeyOrderByCreatedAtDesc(member.ownerKey());
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
	public JdSubmission getOwned(UUID submissionId, UUID memberId) {
		return submissionRepository.findByIdAndMemberId(submissionId, memberId)
			.orElseThrow(() -> new IllegalArgumentException("submission not found: " + submissionId));
	}
}
