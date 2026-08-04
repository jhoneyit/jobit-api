package com.jobit.llm;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * LLM 호출 직전의 지출 방어 (스펙 §6 비용 통제).
 *
 * <p><b>두 겹이다.</b>
 * <ul>
 *   <li><b>소유자별 호출 한도</b> — 한 사람이 폭주하는 것을 막는다.
 *   <li><b>전역 일일 비용 상한</b> — 여러 사람이 동시에 몰려도 총액을 막는다.
 * </ul>
 *
 * <p>호출 횟수만 막으면 "많은 사람이 조금씩" 쓰는 경우를 못 막고, 총액만 막으면 한 사람이
 * 하루 예산을 다 태워 다른 사람을 막아 버린다. 둘 다 필요하다.
 *
 * <p><b>왜 인터셉터가 아니라 서비스에서 부르는가.</b> 캐시 적중은 LLM을 부르지 않으므로 한도를
 * 소비하면 안 된다 — 캐시가 있을수록 사용자가 덜 막히는 게 맞다. 그 판단은 요청이 들어오는
 * 시점이 아니라 캐시를 조회한 뒤에야 가능하다. 그래서 <b>돈이 나가기 직전 한 지점</b>에서만
 * 부른다. 지금 그 지점은 두 곳이다 — JD 파싱과 질문 생성.
 */
@Component
@Slf4j
public class LlmGuard {

	private final JdbcClient jdbc;

	private final int callsPerHour;

	private final BigDecimal dailyBudgetUsd;

	public LlmGuard(JdbcClient jdbc,
			@Value("${jobit.llm.calls-per-hour:20}") int callsPerHour,
			@Value("${jobit.llm.daily-budget-usd:5.00}") BigDecimal dailyBudgetUsd) {
		this.jdbc = jdbc;
		this.callsPerHour = callsPerHour;
		this.dailyBudgetUsd = dailyBudgetUsd;
		log.info("LLM 지출 방어: 소유자당 시간당 {}회, 전역 일일 ${}", callsPerHour, dailyBudgetUsd);
	}

	/**
	 * 호출 1건을 소비한다. 한도를 넘으면 던진다.
	 *
	 * <p><b>{@code REQUIRES_NEW} 인 이유:</b> 호출부 트랜잭션이 나중에 롤백되어도 소비는 남아야
	 * 한다. 같은 트랜잭션에 묶으면 실패한 요청이 한도를 되돌려 받아, 실패를 반복하는 것만으로
	 * 무한정 호출할 수 있다.
	 *
	 * @param ownerKey {@code user:<id>} 또는 {@code anon:<쿠키>}. null 이면 소유자 한도는 건너뛰고
	 *                 전역 상한만 본다 — 소유자를 모른다고 문을 열어 두지는 않는다
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void checkAndConsume(String ownerKey) {
		checkDailyBudget();

		if (ownerKey == null || ownerKey.isBlank()) {
			log.debug("owner_key 가 없어 소유자 한도를 건너뜁니다 (전역 상한은 적용됨)");
			return;
		}
		if (callsPerHour <= 0) {
			return; // 0 이면 끈다 (로컬 개발용)
		}

		OffsetDateTime window = OffsetDateTime.now().truncatedTo(ChronoUnit.HOURS);

		// 원자적 증가. 조회 후 증가로 나누면 동시 요청이 같은 값을 읽어 한도를 넘긴다.
		Integer count = jdbc.sql("""
				insert into rate_limit_bucket (owner_key, window_start, count)
				values (:ownerKey, :window, 1)
				on conflict (owner_key, window_start)
				do update set count = rate_limit_bucket.count + 1
				returning count
				""")
			.param("ownerKey", ownerKey)
			.param("window", window)
			.query(Integer.class)
			.single();

		if (count > callsPerHour) {
			long retryAfter = Duration.between(OffsetDateTime.now(), window.plusHours(1))
				.toSeconds();
			throw new RateLimitExceededException(Math.max(1, retryAfter));
		}
	}

	/**
	 * 오늘 누적 비용이 상한을 넘었는지 본다.
	 *
	 * <p><b>횟수가 아니라 금액으로 막는 이유:</b> 기능마다 단가가 열 배씩 차이 난다. JD 파싱
	 * 100회와 질문 생성 100회는 전혀 다른 지출이다. {@code llm_call_log} 에 실제 비용이 이미
	 * 쌓이고 있으므로 그걸 그대로 쓴다.
	 *
	 * <p>기준 시각은 서버 로컬 타임존의 자정이다. 사용자가 아니라 운영자가 보는 지표이므로
	 * UTC 가 아니라 로컬이 자연스럽다.
	 */
	private void checkDailyBudget() {
		if (dailyBudgetUsd.signum() <= 0) {
			return; // 0 이하면 끈다
		}

		BigDecimal spent = jdbc
			.sql("select coalesce(sum(cost_usd), 0) from llm_call_log where created_at >= :since")
			.param("since", OffsetDateTime.now().truncatedTo(ChronoUnit.DAYS))
			.query(BigDecimal.class)
			.single();

		if (spent.compareTo(dailyBudgetUsd) >= 0) {
			log.warn("일일 비용 상한 도달: ${} / ${}", spent, dailyBudgetUsd);
			throw new DailyBudgetExceededException(spent, dailyBudgetUsd);
		}
	}

	/** 만료된 창을 치운다. 조회는 PK 로만 하므로 남아 있어도 틀리진 않지만 무한정 쌓인다. */
	@Transactional
	public int purgeExpired() {
		return jdbc.sql("delete from rate_limit_bucket where window_start < :cutoff")
			.param("cutoff", OffsetDateTime.now().minusHours(2))
			.update();
	}

	/** 소유자별 시간당 한도 초과. */
	public static class RateLimitExceededException extends RuntimeException {

		private final long retryAfterSeconds;

		public RateLimitExceededException(long retryAfterSeconds) {
			super("요청 한도를 초과했습니다.");
			this.retryAfterSeconds = retryAfterSeconds;
		}

		public long getRetryAfterSeconds() {
			return retryAfterSeconds;
		}
	}

	/**
	 * 전역 일일 비용 상한 도달.
	 *
	 * <p>사용자 잘못이 아니므로 문구를 소유자 한도와 다르게 둔다 — "잠시 후 다시"가 아니라
	 * "오늘은 여기까지"다.
	 */
	public static class DailyBudgetExceededException extends RuntimeException {

		public DailyBudgetExceededException(BigDecimal spent, BigDecimal limit) {
			super("오늘 사용 한도에 도달했습니다. 내일 다시 시도해 주세요. (%s / %s USD)"
				.formatted(spent.setScale(2, java.math.RoundingMode.HALF_UP), limit));
		}
	}
}
