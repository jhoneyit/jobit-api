package com.jobit.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobit.PostgresTestContainer;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 지출 방어 검증.
 *
 * <p>실제 Postgres 를 띄운다 — 원자적 증가({@code on conflict do update ... returning})가 이
 * 구현의 핵심이라 인메모리 대역으로는 검증이 되지 않는다.
 */
class LlmGuardTest {

	private static final String OWNER = "anon:" + UUID.randomUUID();

	@Nested
	@SpringBootTest(properties = { "jobit.llm.calls-per-hour=3",
			"jobit.llm.daily-budget-usd=1000" })
	@Import(PostgresTestContainer.class)
	@DisplayName("소유자별 시간당 한도")
	class PerOwner {

		@Autowired
		private LlmGuard guard;

		@Autowired
		private JdbcClient jdbc;

		@BeforeEach
		void clean() {
			jdbc.sql("delete from rate_limit_bucket").update();
		}

		@Test
		@DisplayName("한도까지는 통과하고 넘으면 막는다")
		void blocksBeyondLimit() {
			for (int i = 1; i <= 3; i++) {
				int attempt = i;
				assertThatCode(() -> guard.checkAndConsume(OWNER)).as("%d번째", attempt)
					.doesNotThrowAnyException();
			}

			assertThatThrownBy(() -> guard.checkAndConsume(OWNER))
				.isInstanceOf(LlmGuard.RateLimitExceededException.class);
		}

		@Test
		@DisplayName("소유자가 다르면 서로의 한도를 쓰지 않는다")
		void isolatesOwners() {
			String other = "user:" + UUID.randomUUID();
			for (int i = 0; i < 3; i++) {
				guard.checkAndConsume(OWNER);
			}

			assertThatCode(() -> guard.checkAndConsume(other)).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("owner_key 가 없어도 전역 상한은 여전히 본다 — 문을 열어 두지 않는다")
		void stillChecksGlobalWithoutOwner() {
			// 소유자 한도는 건너뛰지만 예외 없이 통과해야 한다 (전역 상한이 넉넉하므로).
			assertThatCode(() -> guard.checkAndConsume(null)).doesNotThrowAnyException();
			assertThat(jdbc.sql("select count(*) from rate_limit_bucket").query(Integer.class)
				.single()).as("owner_key 가 없으면 버킷을 만들지 않는다").isZero();
		}

		@Test
		@DisplayName("만료된 창만 정리한다 — 현재 창은 남긴다")
		void purgesOnlyExpired() {
			guard.checkAndConsume(OWNER);
			jdbc.sql("""
					insert into rate_limit_bucket (owner_key, window_start, count)
					values ('anon:old', now() - interval '5 hours', 99)
					""").update();

			guard.purgeExpired();

			assertThat(jdbc.sql("select count(*) from rate_limit_bucket").query(Integer.class)
				.single()).isEqualTo(1);
		}
	}

	@Nested
	@SpringBootTest(properties = { "jobit.llm.calls-per-hour=1000",
			"jobit.llm.daily-budget-usd=0.10" })
	@Import(PostgresTestContainer.class)
	@DisplayName("전역 일일 비용 상한")
	class DailyBudget {

		@Autowired
		private LlmGuard guard;

		@Autowired
		private JdbcClient jdbc;

		@BeforeEach
		void clean() {
			jdbc.sql("delete from llm_call_log").update();
			jdbc.sql("delete from rate_limit_bucket").update();
		}

		private void spend(String amount) {
			jdbc.sql("""
					insert into llm_call_log
					  (feature, model, input_tokens, output_tokens, cost_usd, latency_ms)
					values ('JD_PARSE', 'test', 0, 0, :cost, 0)
					""").param("cost", new BigDecimal(amount)).update();
		}

		@Test
		@DisplayName("상한 아래면 통과한다")
		void allowsUnderBudget() {
			spend("0.05");

			assertThatCode(() -> guard.checkAndConsume("anon:x")).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("상한에 닿으면 소유자 한도와 무관하게 막는다")
		void blocksAtBudget() {
			spend("0.10");

			assertThatThrownBy(() -> guard.checkAndConsume("anon:" + UUID.randomUUID()))
				.isInstanceOf(LlmGuard.DailyBudgetExceededException.class)
				.hasMessageContaining("오늘 사용 한도");
		}

		@Test
		@DisplayName("어제 쓴 비용은 오늘 한도에 포함되지 않는다")
		void ignoresYesterday() {
			jdbc.sql("""
					insert into llm_call_log
					  (feature, model, input_tokens, output_tokens, cost_usd, latency_ms, created_at)
					values ('JD_PARSE', 'test', 0, 0, 99.0, 0, now() - interval '2 days')
					""").update();

			assertThatCode(() -> guard.checkAndConsume("anon:y")).doesNotThrowAnyException();
		}
	}
}
