package com.jobit.gap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.jobit.common.NotFoundException;
import com.jobit.jd.Requirement;
import com.jobit.llm.LlmGuard;
import com.jobit.resume.ResumeBullet;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 리라이트 흐름의 규칙 (스펙 §4.4). {@code GapAnalysisServiceTest} 와 같은 원칙 —
 * 고정하는 것은 순서와 조건이다.
 */
class RewriteServiceTest {

	private static final String OWNER = "user:u-1";

	private static final UUID ITEM_ID = UUID.randomUUID();

	private GapItemRepository gapItemRepository;

	private RewriteSuggestionRepository suggestionRepository;

	private Rewriter rewriter;

	private LlmGuard llmGuard;

	private RewriteService service;

	private GapItem item;

	private ResumeBullet bullet;

	@BeforeEach
	void setUp() {
		gapItemRepository = mock(GapItemRepository.class);
		suggestionRepository = mock(RewriteSuggestionRepository.class);
		rewriter = mock(Rewriter.class);
		llmGuard = mock(LlmGuard.class);

		TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
		given(transactionTemplate.execute(any())).willAnswer(invocation -> {
			TransactionCallback<?> callback = invocation.getArgument(0);
			return callback.doInTransaction(mock(TransactionStatus.class));
		});

		bullet = mock(ResumeBullet.class);
		given(bullet.getText()).willReturn("정산 배치를 운영했습니다");

		Requirement requirement = mock(Requirement.class);
		given(requirement.getText()).willReturn("대용량 트래픽 처리 경험");

		item = mock(GapItem.class);
		given(item.getId()).willReturn(ITEM_ID);
		given(item.getStatus()).willReturn(GapItem.Status.WEAK);
		given(item.getEvidenceBullet()).willReturn(bullet);
		given(item.getRequirement()).willReturn(requirement);
		given(item.getRationale()).willReturn("언급은 있으나 수치가 없다");

		given(gapItemRepository.findOwned(ITEM_ID, OWNER)).willReturn(Optional.of(item));
		given(suggestionRepository.findByGapItemId(ITEM_ID)).willReturn(Optional.empty());
		given(suggestionRepository.save(any(RewriteSuggestion.class))).willAnswer(invocation -> {
			RewriteSuggestion suggestion = invocation.getArgument(0);
			ReflectionTestUtils.setField(suggestion, "id", UUID.randomUUID());
			return suggestion;
		});

		service = new RewriteService(gapItemRepository, suggestionRepository, rewriter, llmGuard,
				transactionTemplate);
	}

	@Test
	@DisplayName("제안이 이미 있으면 LLM 도 한도도 타지 않는다")
	void cacheHitSkipsEverything() {
		RewriteSuggestion existing = mock(RewriteSuggestion.class);
		given(suggestionRepository.findByGapItemId(ITEM_ID)).willReturn(Optional.of(existing));

		RewriteService.Result result = service.rewrite(OWNER, ITEM_ID);

		assertThat(result.cached()).isTrue();
		assertThat(result.suggestion()).isSameAs(existing);
		then(llmGuard).should(never()).checkAndConsume(anyString());
		then(rewriter).should(never()).rewrite(any());
	}

	@Test
	@DisplayName("WEAK 만 리라이트한다 — MET 은 고칠 이유가, MISSING 은 고칠 문장이 없다")
	void rejectsNonWeakItems() {
		for (GapItem.Status status : new GapItem.Status[] { GapItem.Status.MET,
				GapItem.Status.MISSING }) {
			given(item.getStatus()).willReturn(status);

			assertThatThrownBy(() -> service.rewrite(OWNER, ITEM_ID))
				.isInstanceOf(RewriteService.NotRewritableException.class);
		}
		then(llmGuard).should(never()).checkAndConsume(anyString());
		then(rewriter).should(never()).rewrite(any());
	}

	@Test
	@DisplayName("정상 흐름 — 원문·요구사항·판정 이유가 그대로 전달되고 원문이 저장된다")
	void generatesAndPersists() {
		given(rewriter.rewrite(any()))
			.willReturn(new Rewriter.Suggestion("[규모]건의 정산 배치를 운영했습니다", "규모를 드러냈다"));

		RewriteService.Result result = service.rewrite(OWNER, ITEM_ID);

		assertThat(result.cached()).isFalse();
		then(llmGuard).should().checkAndConsume(OWNER);

		ArgumentCaptor<Rewriter.Request> request = ArgumentCaptor.forClass(Rewriter.Request.class);
		then(rewriter).should().rewrite(request.capture());
		assertThat(request.getValue().requirementText()).isEqualTo("대용량 트래픽 처리 경험");
		assertThat(request.getValue().rationale()).isEqualTo("언급은 있으나 수치가 없다");
		assertThat(request.getValue().bulletText()).isEqualTo("정산 배치를 운영했습니다");

		assertThat(result.suggestion().getOriginal()).isEqualTo("정산 배치를 운영했습니다");
		assertThat(result.suggestion().getSuggested()).isEqualTo("[규모]건의 정산 배치를 운영했습니다");
	}

	@Test
	@DisplayName("동시 리라이트 경합이면 먼저 저장된 제안을 돌려준다 — V12 유니크가 최종 방어선이다")
	void returnsWinnerOnConflict() {
		given(rewriter.rewrite(any())).willReturn(new Rewriter.Suggestion("고친 문장", "이유"));
		given(suggestionRepository.save(any(RewriteSuggestion.class)))
			.willThrow(new DataIntegrityViolationException("duplicate"));

		RewriteSuggestion winner = mock(RewriteSuggestion.class);
		// 첫 조회(캐시 미스)는 빈 값, 경합 후 재조회는 이긴 쪽을 돌려준다.
		given(suggestionRepository.findByGapItemId(ITEM_ID)).willReturn(Optional.empty(),
				Optional.of(winner));

		RewriteService.Result result = service.rewrite(OWNER, ITEM_ID);

		assertThat(result.cached()).isTrue();
		assertThat(result.suggestion()).isSameAs(winner);
	}

	@Test
	@DisplayName("남의 항목이면 존재도 알려주지 않는다")
	void hidesOthersItems() {
		assertThatThrownBy(() -> service.rewrite("user:someone-else", ITEM_ID))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	@DisplayName("채택 여부를 기록한다 — 품질 지표라 철회도 가능해야 한다")
	void recordsAcceptance() {
		RewriteSuggestion suggestion = mock(RewriteSuggestion.class);
		given(suggestionRepository.findOwned(any(), anyString()))
			.willReturn(Optional.of(suggestion));

		service.setAccepted(OWNER, UUID.randomUUID(), true);
		then(suggestion).should().accept();

		service.setAccepted(OWNER, UUID.randomUUID(), false);
		then(suggestion).should().reject();
	}
}
