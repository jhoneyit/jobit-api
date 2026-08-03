package com.jobit.llm;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * LLM 호출 1건의 비용 기록. 스펙 §3.5.
 *
 * <p>새 LLM 호출 경로를 만들면 여기에 남기는 것을 함께 넣는다. 나중에 붙이려면 귀찮고, 없으면
 * 어느 기능이 돈을 먹는지 안 보인다.
 */
@Entity
@Table(name = "llm_call_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LlmCallLog {

	@Id
	@GeneratedValue
	private UUID id;

	/** 어느 기능이 호출했는가. 예: {@code JD_PARSE}, {@code QUESTION_GEN}, {@code GAP_JUDGE}. */
	@Column(nullable = false, length = 40)
	private String feature;

	@Column(nullable = false)
	private String model;

	@Column(name = "input_tokens", nullable = false)
	private int inputTokens;

	@Column(name = "output_tokens", nullable = false)
	private int outputTokens;

	/**
	 * 프롬프트 캐시 토큰. 읽기와 생성은 단가가 다르므로 입력 토큰에 합산하면 비용이 틀어진다
	 * (V5 마이그레이션).
	 */
	@Column(name = "cache_read_tokens", nullable = false)
	private int cacheReadTokens;

	@Column(name = "cache_creation_tokens", nullable = false)
	private int cacheCreationTokens;

	@Column(name = "cost_usd", nullable = false, precision = 12, scale = 6)
	private BigDecimal costUsd;

	@Column(name = "cache_hit", nullable = false)
	private boolean cacheHit;

	@Column(name = "latency_ms", nullable = false)
	private int latencyMs;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	public LlmCallLog(String feature, String model, int inputTokens, int outputTokens,
			int cacheReadTokens, int cacheCreationTokens, BigDecimal costUsd, boolean cacheHit,
			int latencyMs) {
		this.feature = feature;
		this.model = model;
		this.inputTokens = inputTokens;
		this.outputTokens = outputTokens;
		this.cacheReadTokens = cacheReadTokens;
		this.cacheCreationTokens = cacheCreationTokens;
		this.costUsd = costUsd;
		this.cacheHit = cacheHit;
		this.latencyMs = latencyMs;
	}
}
