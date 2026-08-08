package com.jobit.resume;

import com.jobit.common.OwnerKey;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 이력서 엔드포인트 (스펙 §3.3, docs/api.md).
 *
 * <p><b>{@code X-Owner-Key} 가 전부 필수다.</b> JD 파싱은 선택이었지만(결과가 공용 자산이라
 * 익명으로도 의미가 있다) 이력서는 처음부터 끝까지 개인 자산이라 소유자 없이 할 수 있는 일이 없다.
 *
 * <p>컨트롤러는 변환과 검증만 한다. 암호화·분해·임베딩·트랜잭션 경계는 {@link ResumeService} 가 갖는다.
 */
@RestController
@RequestMapping(path = "/api/resumes", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ResumeController {

	private final ResumeService resumeService;

	private final ResumeBulletRepository bulletRepository;

	/**
	 * {@code POST /api/resumes} — 이력서를 올려 문장 단위로 분해하고 임베딩까지 만든다.
	 *
	 * <p><b>느리다.</b> LLM 분해가 수십 초 걸리므로 프론트는 로딩 상태를 반드시 보여 줘야 한다.
	 * JD 파싱과 달리 스트리밍하지 않는 이유는, 문장 목록이 <b>전부 모여야</b> 의미가 있고
	 * (임베딩이 뒤따른다) 중간 결과를 보여 줄 화면도 없기 때문이다.
	 */
	@PostMapping
	public ResumeView.Uploaded upload(@RequestHeader("X-Owner-Key") String ownerKey,
			@Valid @RequestBody ResumeUploadRequest request) {

		return ResumeView.Uploaded
			.of(resumeService.upload(OwnerKey.requireValid(ownerKey), request.text()));
	}

	/**
	 * {@code GET /api/resumes} — 내 이력서 목록.
	 *
	 * <p>문장 수는 <b>한 번의 집계 쿼리</b>로 모은다. 줄마다 세면 그대로 N+1 이다
	 * ({@code JdSubmissionService} 와 같은 이유).
	 */
	@GetMapping
	public ResumeView.ListResponse list(@RequestHeader("X-Owner-Key") String ownerKey) {
		List<Resume> resumes = resumeService.listOwned(OwnerKey.requireValid(ownerKey));
		if (resumes.isEmpty()) {
			return new ResumeView.ListResponse(List.of());
		}

		Map<UUID, Long> counts = countBullets(resumes);

		List<ResumeView.Summary> items = new ArrayList<>(resumes.size());
		for (Resume resume : resumes) {
			// 문장이 0개면 집계 결과에 행 자체가 없다 (group by). 0으로 채운다.
			int count = counts.getOrDefault(resume.getId(), 0L).intValue();
			items.add(new ResumeView.Summary(resume.getId(), count, resume.getCreatedAt(),
					resume.getExpiresAt()));
		}
		return new ResumeView.ListResponse(items);
	}

	/**
	 * {@code GET /api/resumes/{resumeId}} — 분해된 문장 목록.
	 *
	 * <p>없거나 남의 것이면 404다 — 403이면 자원의 존재 여부가 새어 나간다
	 * ({@code NotFoundException} 참고).
	 */
	@GetMapping("/{resumeId}")
	public ResumeView.Detail detail(@PathVariable UUID resumeId,
			@RequestHeader("X-Owner-Key") String ownerKey) {

		return ResumeView.Detail
			.of(resumeService.getOwned(resumeId, OwnerKey.requireValid(ownerKey)));
	}

	/** {@code DELETE /api/resumes/{resumeId}} — 문장과 벡터까지 함께 지운다. */
	@DeleteMapping("/{resumeId}")
	public ResponseEntity<Void> delete(@PathVariable UUID resumeId,
			@RequestHeader("X-Owner-Key") String ownerKey) {

		resumeService.delete(resumeId, OwnerKey.requireValid(ownerKey));
		return ResponseEntity.noContent().build();
	}

	/**
	 * {@code POST /api/resumes/claim} — 익명으로 올린 이력서를 계정으로 승계한다 (스펙 §3.6).
	 *
	 * <p>방향 강제는 {@code SubmissionController#claim} 과 같은 규약이고 같은 이유다 —
	 * 반대 방향을 허용하면 계정 자산을 익명 키로 빼내는 경로가 생긴다. 이력서는 제출 이력보다
	 * 민감하므로 더더욱 한 방향이어야 한다.
	 */
	@PostMapping("/claim")
	public ClaimResult claim(@RequestHeader("X-Owner-Key") String toOwnerKey,
			@RequestBody ClaimRequest request) {

		OwnerKey.requireValid(toOwnerKey);
		OwnerKey.requireValid(request.fromOwnerKey());

		if (!toOwnerKey.startsWith(OwnerKey.USER_PREFIX)) {
			throw new IllegalArgumentException("claim target must be a user key");
		}
		if (!OwnerKey.isAnonymous(request.fromOwnerKey())) {
			throw new IllegalArgumentException("claim source must be an anonymous key");
		}

		return new ClaimResult(resumeService.claim(request.fromOwnerKey(), toOwnerKey));
	}

	private Map<UUID, Long> countBullets(List<Resume> resumes) {
		List<UUID> ids = new ArrayList<>(resumes.size());
		for (Resume resume : resumes) {
			ids.add(resume.getId());
		}

		Map<UUID, Long> counts = new HashMap<>();
		for (ResumeBulletRepository.BulletCount row : bulletRepository.countByResumeIds(ids)) {
			counts.put(row.getResumeId(), row.getCount());
		}
		return counts;
	}

	/** {@code fromOwnerKey}의 검증은 {@code OwnerKey.requireValid}가 한다 — null·공백도 거기서 걸린다. */
	public record ClaimRequest(String fromOwnerKey) {
	}

	/** @param moved 실제로 옮겨진 이력서 수 */
	public record ClaimResult(int moved) {
	}
}
