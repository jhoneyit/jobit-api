package com.jobit.interview;

import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만료된 발화 원문 정리 (docs/interview-practice-design.md §7).
 *
 * <p>{@code transcript}는 사용자가 자기 경력을 말한 내용이라 이력서에 준해 다룬다 (스펙 §6).
 * 무기한 보관하지 않겠다는 약속은 {@code transcript_expires_at}을 채우는 것만으로는 지켜지지
 * 않는다 — <b>실제로 지우는 작업이 여기다.</b>
 *
 * <p><b>행을 지우지 않고 {@code transcript}만 비운다.</b> 기록 전체를 지우면 "내 면접 기록"
 * 기능이 죽고, 발화 원문 없이도 점수 추이는 그대로 읽힌다. 화면은 이 상태를
 * {@code answered=true}인데 원문이 없는 것으로 구분해 "보관 기간이 지나 지워졌다"고 알린다
 * (docs/api.md).
 *
 * <p>{@code RateLimitCleanup}과 달리 <b>실행 결과를 info 로 남긴다.</b> 저쪽은 캐시성 정리라
 * 지워졌는지 아닌지가 사후에 문제되지 않지만, 이건 개인정보 삭제 약속의 이행 기록이다 —
 * 돌지 않았다는 사실을 나중에 로그로 확인할 수 있어야 한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TranscriptCleanup {

	/**
	 * 하루 한 번이면 충분하다. TTL 이 90일 단위라 몇 시간의 오차는 의미가 없고, 자주 돌수록
	 * 아무것도 지울 게 없는 스캔만 늘어난다.
	 *
	 * <p>{@code initialDelay}를 두는 이유는 부팅 직후 트래픽과 겹치지 않게 하기 위해서다
	 * ({@code RateLimitCleanup}과 같은 이유).
	 */
	private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;

	private final InterviewAnswerRepository answerRepository;

	@Scheduled(fixedDelay = ONE_DAY_MS, initialDelay = 300_000L)
	@Transactional
	public void purgeExpiredTranscripts() {
		int forgotten = answerRepository.forgetExpiredTranscripts(OffsetDateTime.now());
		if (forgotten > 0) {
			log.info("보관 기간이 지난 면접 답변 원문 {}건을 지웠습니다 (점수·피드백은 유지)", forgotten);
		}
	}
}
