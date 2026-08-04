package com.jobit.stats;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개 통계 (docs/api.md).
 *
 * <p>지금까지 들어온 공고를 집계해 보여준다. <b>개인 자산이 아니라 공유 자산의 집계</b>라
 * {@code owner_key} 를 보지 않는다 — 공고 자체가 {@code content_hash} 로 전역에 하나씩만
 * 존재하는 자산이기 때문이다 (스펙 §3.1).
 *
 * <p><b>왜 프론트가 DB 를 직접 읽지 않는가.</b> 2026-08-04 이관으로 도메인 조회는 이 서버가
 * 갖기로 했다. 화면 하나 때문에 그 경계를 뚫으면 나중에 이관할 코드가 늘어난다.
 */
@RestController
@RequestMapping(path = "/api/stats", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class StatsController {

	/** 화면 한 칸에 들어갈 만큼만. 너무 많으면 순위가 아니라 목록이 된다. */
	private static final int MAX_LIMIT = 20;

	private final JdbcClient jdbc;

	/**
	 * {@code GET /api/stats/stacks} — 기술 스택 등장 빈도 순위.
	 *
	 * <p>{@code parsed.stack} 은 LLM 이 <b>정규화된 표기</b>로 뽑아 둔 값이라 그대로 세면 된다
	 * (프롬프트: "정규화된 표기를 쓴다 — 예: 'Spring Boot'"). 반면 {@code parsed.domain} 은
	 * 자유 서술이라("핀테크 결제", "중고거래 플랫폼 백엔드") 집계 대상이 아니다.
	 */
	@GetMapping("/stacks")
	public StackRanking stacks(@RequestParam(defaultValue = "8") int limit) {
		int capped = Math.clamp(limit, 1, MAX_LIMIT);

		int total = jdbc.sql("select count(*) from job_posting").query(Integer.class).single();

		// count(distinct) 인 이유: 한 공고가 같은 스택을 두 번 담아도 한 번으로 센다.
		List<StackCount> items = jdbc.sql("""
				select name, count(distinct jp.id) as postings
				  from job_posting jp,
				       jsonb_array_elements_text(jp.parsed -> 'stack') as name
				 group by name
				 order by postings desc, name asc
				 limit :limit
				""")
			.param("limit", capped)
			.query((rs, rowNum) -> new StackCount(rs.getString("name"), rs.getInt("postings")))
			.list();

		return new StackRanking(total, items);
	}

	/**
	 * @param totalPostings 전체 공고 수. 화면에서 "3건 중 2건" 처럼 모수를 함께 보여줘야
	 *                      표본이 작을 때 순위를 과대 해석하지 않는다
	 */
	public record StackRanking(int totalPostings, List<StackCount> items) {
	}

	public record StackCount(String name, int postings) {
	}
}
