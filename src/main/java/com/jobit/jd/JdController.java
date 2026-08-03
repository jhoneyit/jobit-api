package com.jobit.jd;

import com.jobit.common.OwnerKey;
import com.jobit.submission.JdSubmissionService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * JD 파싱 엔드포인트 (스펙 §4.1, docs/api.md).
 *
 * <p>컨트롤러는 변환과 검증만 한다. 정규화·해시·캐시·저장은 {@link JdParsingService}가 갖는다.
 */
@RestController
@RequestMapping(path = "/api/jd", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Slf4j
public class JdController {

	private final JdParsingService parsingService;

	private final JdSubmissionService submissionService;

	/**
	 * {@code POST /api/jd/parse} — 공고를 파싱하거나 캐시를 재사용한다.
	 *
	 * <p>{@code X-Owner-Key}는 선택이다. 있으면 입력 이력에 남긴다 (스펙 §3.6). 비로그인 사용자도
	 * 이력을 갖기 때문에 로그인 여부로 분기하지 않는다 — 값의 형식만 본다.
	 *
	 * <p><b>이력 기록 실패가 파싱을 실패시키지 않는다.</b> 사용자가 원한 것은 분석 결과이고,
	 * 이력은 부가 기능이다.
	 */
	@PostMapping("/parse")
	public JdParseResult parse(@Valid @RequestBody JdParseRequest request,
			@RequestHeader(name = "X-Owner-Key", required = false) String ownerKey) {

		JdParsingService.Outcome outcome = parsingService.parseOrGetCached(request.text(),
				request.sourceUrl());

		recordSubmission(ownerKey, outcome);

		List<Requirement> requirements = parsingService.requirementsOf(outcome.jobPosting());
		return JdParseResult.of(outcome.jobPosting(), requirements, outcome.cached());
	}

	private void recordSubmission(String ownerKey, JdParsingService.Outcome outcome) {
		if (ownerKey == null || ownerKey.isBlank()) {
			return;
		}
		try {
			submissionService.record(OwnerKey.requireValid(ownerKey), outcome.jobPosting());
		}
		catch (RuntimeException ex) {
			log.warn("입력 이력 기록 실패 (파싱 결과는 정상 반환): jobPosting={}",
					outcome.jobPosting().getId(), ex);
		}
	}
}
