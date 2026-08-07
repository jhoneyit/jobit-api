package com.jobit.submission;

import com.jobit.common.OwnerKey;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * JD 입력 이력 엔드포인트 (스펙 §3.6·§4.6, docs/api.md).
 *
 * <p><b>왜 프론트가 DB를 직접 읽지 않는가.</b> 2026-08-04 이관으로 도메인 조회는 이 서버가
 * 갖기로 했다. 화면 하나 때문에 그 경계를 뚫으면 나중에 이관할 코드가 늘어난다
 * ({@code StatsController}와 같은 이유).
 *
 * <p>컨트롤러는 변환과 검증만 한다. 소유자 확인·집계·승계 규칙은 {@link JdSubmissionService}가 갖는다.
 */
@RestController
@RequestMapping(path = "/api/submissions", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class SubmissionController {

	/** 한 번에 내려줄 수 있는 최대 줄 수. 화면이 페이지를 쓰지 않아도 목록은 언젠가 길어진다. */
	private static final int MAX_SIZE = 100;

	private final JdSubmissionService submissionService;

	/**
	 * {@code GET /api/submissions} — 내 이력 목록.
	 *
	 * <p><b>{@code X-Owner-Key}가 여기서는 필수다.</b> 파싱과 달리 이건 개인 자산 조회라,
	 * 소유자가 없으면 돌려줄 대상 자체가 없다. 빈 목록으로 얼버무리면 프론트가 "기록이 없다"와
	 * "헤더를 빠뜨렸다"를 구분하지 못한다.
	 */
	@GetMapping
	public SubmissionPage list(@RequestHeader("X-Owner-Key") String ownerKey,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {

		OwnerKey.requireValid(ownerKey);
		Page<SubmissionListItem> found = submissionService.list(ownerKey,
				PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_SIZE)));

		return new SubmissionPage(found.getContent(), found.getNumber(), found.getSize(),
				found.getTotalElements(), found.getTotalPages());
	}

	/**
	 * {@code DELETE /api/submissions/{submissionId}} — 내 목록에서 한 줄을 치운다.
	 *
	 * <p>없거나 남의 것이면 404다 — 403이면 자원의 존재 여부가 새어 나간다
	 * ({@code NotFoundException} 참고).
	 */
	@DeleteMapping("/{submissionId}")
	public ResponseEntity<Void> delete(@PathVariable UUID submissionId,
			@RequestHeader("X-Owner-Key") String ownerKey) {

		submissionService.delete(submissionId, OwnerKey.requireValid(ownerKey));
		return ResponseEntity.noContent().build();
	}

	/**
	 * {@code POST /api/submissions/claim} — 익명으로 쌓은 이력을 계정으로 승계한다 (스펙 §3.6).
	 *
	 * <p><b>방향을 강제한다.</b> 받는 쪽({@code X-Owner-Key})은 {@code user:}, 넘겨줄
	 * 쪽({@code fromOwnerKey})은 {@code anon:}이어야 한다. 반대 방향을 허용하면 계정 기록을
	 * 익명 키로 빼내는 경로가 생긴다 — 승계는 "익명 → 계정" 한 방향으로만 의미가 있다.
	 *
	 * <p>이 규칙을 서비스가 아니라 여기서 검사하는 이유: {@code transferOwnership} 자체는
	 * 방향을 모르는 범용 연산이고(승계 외의 용도가 생길 수 있다), 방향 제약은 이 엔드포인트의
	 * 계약이다 (docs/api.md).
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

		return new ClaimResult(
				submissionService.transferOwnership(request.fromOwnerKey(), toOwnerKey));
	}

	/**
	 * 목록 응답.
	 *
	 * <p>Spring의 {@code Page}를 그대로 직렬화하지 않는다 — 직렬화 형태가 Spring 버전에 묶여
	 * 있어서, 우리가 고치지 않아도 프론트와의 계약이 바뀔 수 있다.
	 */
	public record SubmissionPage(List<SubmissionListItem> items, int page, int size,
			long totalElements, int totalPages) {
	}

	/** {@code fromOwnerKey}의 검증은 {@code OwnerKey.requireValid}가 한다 — null·공백도 거기서 걸린다. */
	public record ClaimRequest(String fromOwnerKey) {
	}

	/** @param moved 실제로 옮겨진 줄 수. 계정에 이미 있어 버려진 줄은 세지 않는다 */
	public record ClaimResult(int moved) {
	}
}
