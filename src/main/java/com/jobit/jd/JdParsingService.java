package com.jobit.jd;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * JD 파싱 흐름 (스펙 §4.1).
 *
 * <pre>
 * 1. 본문 정규화 → 해시
 * 2. content_hash로 조회 → 있으면 그대로 재사용 (LLM 호출 없음)
 * 3. 없으면 파싱 → job_posting + requirement 저장
 * </pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JdParsingService {

	private final JobPostingRepository jobPostingRepository;

	private final RequirementRepository requirementRepository;

	private final JdParser jdParser;

	/**
	 * 캐시가 있으면 재사용하고, 없으면 파싱해 저장한다.
	 *
	 * <p>같은 공고를 두 사용자가 동시에 넣으면 둘 다 캐시 미스로 판단해 나란히 파싱할 수 있다.
	 * {@code content_hash} 유니크 제약이 최종 방어선이며, 충돌하면 이미 저장된 쪽을 쓴다.
	 * LLM 호출 한 번이 낭비되지만 락을 잡는 것보다 낫다 — 흔한 상황이 아니다.
	 */
	@Transactional
	public JobPosting parseOrGetCached(String rawText, String sourceUrl) {
		String contentHash = JdTextNormalizer.contentHash(rawText);

		var cached = jobPostingRepository.findByContentHash(contentHash);
		if (cached.isPresent()) {
			log.debug("JD cache hit: {}", contentHash);
			return cached.get();
		}

		JdParser.ParsedJd parsed = jdParser.parse(JdTextNormalizer.normalize(rawText));

		try {
			return save(contentHash, rawText, sourceUrl, parsed);
		}
		catch (DataIntegrityViolationException ex) {
			// 동시 요청이 먼저 저장했다. 그쪽 결과를 쓴다.
			log.debug("JD cache race lost, reusing existing: {}", contentHash);
			return jobPostingRepository.findByContentHash(contentHash).orElseThrow(() -> ex);
		}
	}

	private JobPosting save(String contentHash, String rawText, String sourceUrl,
			JdParser.ParsedJd parsed) {
		JobPosting posting = jobPostingRepository.save(new JobPosting(contentHash, rawText,
				sourceUrl, parsed.company(), parsed.title(), parsed.parsedJson()));

		List<Requirement> requirements = new ArrayList<>();
		List<JdParser.ParsedRequirement> source = parsed.requirements();
		for (int i = 0; i < source.size(); i++) {
			JdParser.ParsedRequirement r = source.get(i);
			requirements.add(new Requirement(posting, r.text(), r.kind(),
					r.keywords().toArray(String[]::new), i));
		}
		requirementRepository.saveAll(requirements);

		log.info("JD parsed: hash={} requirements={}", contentHash, requirements.size());
		return posting;
	}

	@Transactional(readOnly = true)
	public List<Requirement> requirementsOf(JobPosting posting) {
		return requirementRepository.findByJobPostingIdOrderBySortOrder(posting.getId());
	}
}
