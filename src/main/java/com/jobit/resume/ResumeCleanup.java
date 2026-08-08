package com.jobit.resume;

import java.time.Clock;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보관 기간이 지난 이력서 정리 (스펙 §6 개인정보).
 *
 * <p><b>{@code expires_at} 을 채우는 것만으로는 약속이 지켜지지 않는다.</b> 실제로 지우는 작업이
 * 여기다 — {@code TranscriptCleanup} 과 같은 이유로 존재하고, 같은 이유로 결과를 {@code info} 로
 * 남긴다: 개인정보 삭제 약속의 이행 기록이라 <b>돌지 않았다는 사실</b>을 나중에 로그로 확인할 수
 * 있어야 한다.
 *
 * <p>지워지는 것은 이력서 행 하나가 아니라 그 아래 문장·벡터·갭 분석까지다
 * ({@code on delete cascade}). 그래서 로그에는 이력서 수만 남긴다 — 문장 수까지 세려면 지우기 전에
 * 조회를 한 번 더 해야 하는데, 지워질 데이터를 세자고 개인정보를 한 번 더 읽는 것은 앞뒤가 맞지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ResumeCleanup {

	/** {@code TranscriptCleanup} 과 같은 주기·같은 이유. TTL 이 90일 단위라 몇 시간의 오차는 무의미하다. */
	private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;

	private final ResumeRepository resumeRepository;

	private final Clock clock;

	/**
	 * {@code initialDelay} 를 {@code TranscriptCleanup}(5분)과 다르게 둔다. 같은 시각에 두 정리
	 * 작업이 겹치면 부팅 직후에 삭제 트랜잭션이 한꺼번에 몰린다 — 서로 다른 테이블이라 락이
	 * 부딪히지는 않지만, 굳이 겹칠 이유도 없다.
	 */
	@Scheduled(fixedDelay = ONE_DAY_MS, initialDelay = 600_000L)
	@Transactional
	public void purgeExpiredResumes() {
		int deleted = resumeRepository.deleteExpired(OffsetDateTime.now(clock));
		if (deleted > 0) {
			log.info("보관 기간이 지난 이력서 {}건을 지웠습니다 (문장·벡터 포함)", deleted);
		}
	}
}
