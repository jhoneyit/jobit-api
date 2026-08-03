package com.jobit.member;

/**
 * 재설정 링크 전달 포트 (스펙 §3.7).
 *
 * <p>메일 발송 수단(SES / Resend / SMTP)이 아직 정해지지 않았다 — 스펙 §7 미정 항목이다.
 * 정해지기 전까지 이 인터페이스의 기본 구현은 링크를 로그에만 남긴다.
 *
 * <p>토큰 원문이 지나가는 유일한 경로다. 구현체는 이 값을 <b>로그·모니터링·에러 리포트에
 * 남기지 않아야 한다</b> — 개발용 기본 구현만 예외다.
 */
public interface ResetLinkSender {

	/**
	 * @param rawToken 해싱 전 원문. 저장하지 말 것.
	 */
	void send(String email, String rawToken);
}
