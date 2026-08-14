package com.jobit.gap;

import com.jobit.common.NotFoundException;
import com.jobit.llm.LlmGuard;
import com.jobit.resume.ResumeBullet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 리라이트 흐름 (스펙 §4.4).
 *
 * <pre>
 * 1. 소유자 검사 + 캐시 — 제안은 gap_item 당 하나 (V12 유니크). 있으면 그대로 돌려준다
 * 2. WEAK 검사          — MET 은 고칠 이유가 없고, MISSING 은 고칠 문장 자체가 없다
 * 3. 한도 확인          — GPU 시간이 나가기 직전
 * 4. 리라이트 (LLM)     — thinking 이 켜져 수십 초. 트랜잭션 밖이다
 * 5. 저장               — 경합은 유니크 제약 + 재조회 (GapAnalysisService 와 같은 처리)
 * </pre>
 *
 * <p><b>이력서 원문을 복호화하지 않는다.</b> 리라이트가 고치는 것은 {@code resume_bullet.text}
 * (평문 저장) 하나다 — "리라이트는 문장 단위, 이력서 전체를 LLM 에 보내지 않는다"(작업 원칙)를
 * 지키면 원문이 필요한 지점이 아예 생기지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RewriteService {

	private final GapItemRepository gapItemRepository;

	private final RewriteSuggestionRepository suggestionRepository;

	private final Rewriter rewriter;

	private final LlmGuard llmGuard;

	private final TransactionTemplate transactionTemplate;

	/**
	 * 수정안을 만들거나, 이미 있으면 그 제안을 돌려준다.
	 *
	 * <p><b>캐시가 상태 검사보다 먼저다.</b> 제안이 이미 있다는 것은 만들 당시 WEAK 였다는
	 * 뜻이므로, 있는 제안은 상태와 무관하게 계속 조회된다 — 재분석으로 항목이 바뀌면 분석 자체가
	 * 새로 만들어져({@code GapAnalysisService} 캐시 키) 옛 제안은 함께 사라진다.
	 */
	public Result rewrite(String ownerKey, UUID gapItemId) {
		GapItem item = gapItemRepository.findOwned(gapItemId, ownerKey)
			.orElseThrow(() -> new NotFoundException("gap item not found: " + gapItemId));

		return suggestionRepository.findByGapItemId(gapItemId)
			.map(existing -> new Result(existing, true))
			.orElseGet(() -> generate(ownerKey, item));
	}

	private Result generate(String ownerKey, GapItem item) {
		if (item.getStatus() != GapItem.Status.WEAK) {
			throw new NotRewritableException(item.getStatus());
		}
		ResumeBullet bullet = item.getEvidenceBullet();
		if (bullet == null) {
			// 정규화가 "WEAK 는 근거가 있다"를 보장하므로 정상 경로에서 올 수 없다.
			throw new IllegalStateException("WEAK item without evidence: " + item.getId());
		}

		llmGuard.checkAndConsume(ownerKey);

		Rewriter.Suggestion suggestion = rewriter.rewrite(new Rewriter.Request(
				item.getRequirement().getText(), item.getRationale(), bullet.getText()));

		try {
			return transactionTemplate.execute(status -> {
				RewriteSuggestion saved = suggestionRepository.save(new RewriteSuggestion(item,
						bullet, bullet.getText(), suggestion.suggested(), suggestion.reason()));
				log.info("리라이트 저장: gapItem={} suggestion={}", item.getId(), saved.getId());
				return new Result(saved, false);
			});
		}
		catch (DataIntegrityViolationException ex) {
			// 같은 항목을 동시에 리라이트한 경합 — 유니크 제약(V12)이 한쪽을 이겼다.
			log.info("리라이트 경합: gapItem={} — 먼저 저장된 제안을 재사용한다", item.getId());
			RewriteSuggestion winner = suggestionRepository.findByGapItemId(item.getId())
				.orElseThrow(() -> new IllegalStateException(
						"suggestion vanished after conflict: " + item.getId()));
			return new Result(winner, true);
		}
	}

	/**
	 * 채택 여부를 기록한다 — 단순 플래그가 아니라 <b>품질 지표</b>다 (스펙 §3.4). 어떤 수정안이
	 * 실제로 채택되는지가 프롬프트 개선의 근거가 된다.
	 */
	@Transactional
	public Result setAccepted(String ownerKey, UUID suggestionId, boolean accepted) {
		RewriteSuggestion suggestion = suggestionRepository.findOwned(suggestionId, ownerKey)
			.orElseThrow(() -> new NotFoundException("suggestion not found: " + suggestionId));

		if (accepted) {
			suggestion.accept();
		}
		else {
			suggestion.reject();
		}
		return new Result(suggestion, true);
	}

	/**
	 * @param cached true 면 LLM 을 부르지 않고 기존 제안을 돌려준 것이다
	 */
	public record Result(RewriteSuggestion suggestion, boolean cached) {
	}

	/** WEAK 가 아닌 항목의 리라이트 시도. 사용자가 할 일이 없는 항목이라 문구가 그걸 설명해야 한다. */
	public static class NotRewritableException extends RuntimeException {

		private final GapItem.Status status;

		public NotRewritableException(GapItem.Status status) {
			super("cannot rewrite item with status: " + status);
			this.status = status;
		}

		public GapItem.Status getStatus() {
			return status;
		}
	}
}
