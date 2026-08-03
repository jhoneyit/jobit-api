package com.jobit.member;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비밀번호 재설정 (스펙 §3.7).
 *
 * <p>토큰 원문은 {@link ResetLinkSender}로 넘기는 순간 외에는 어디에도 남기지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetService {

	static final Duration TOKEN_TTL = Duration.ofMinutes(30);

	static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

	private final MemberRepository memberRepository;

	private final PasswordResetTokenRepository tokenRepository;

	private final LocalAccountService localAccountService;

	private final ResetLinkSender resetLinkSender;

	private final Clock clock;

	/**
	 * 재설정 메일을 요청한다.
	 *
	 * <p><b>호출자에게 결과를 알려주지 않는다 (void).</b> 성공·실패를 구분해 반환하면 화면 문구가
	 * 갈리고, 그것으로 가입 여부와 가입 수단이 조회된다 (스펙 §6). 존재하지 않는 이메일, GitHub
	 * 가입자, 쿨다운 중인 요청 모두 조용히 넘어간다.
	 */
	@Transactional
	public void requestReset(String email) {
		if (email == null || !EmailNormalizer.isValid(email)) {
			return;
		}

		Optional<Member> found = memberRepository
			.findByProviderAndProviderUid(Member.Provider.LOCAL, EmailNormalizer.normalize(email));
		if (found.isEmpty()) {
			// GitHub 가입자도 여기로 떨어진다 — LOCAL로 조회했기 때문이다.
			return;
		}

		Member member = found.get();
		OffsetDateTime now = OffsetDateTime.now(clock);
		if (withinCooldown(member, now)) {
			log.debug("Password reset within cooldown, ignored: member={}", member.getId());
			return;
		}

		invalidatePrevious(member, now);

		String rawToken = ResetTokens.generate();
		tokenRepository.save(new PasswordResetToken(member, ResetTokens.hash(rawToken),
				now.plus(TOKEN_TTL)));

		resetLinkSender.send(member.getEmail(), rawToken);
		log.info("Password reset requested: member={}", member.getId());
	}

	/**
	 * 토큰으로 새 비밀번호를 설정한다.
	 *
	 * @throws PasswordResetException 토큰이 없거나 만료·사용됐거나, 새 비밀번호가 정책에 맞지 않을 때
	 */
	@Transactional
	public Member confirmReset(String rawToken, String newPassword) {
		if (rawToken == null || rawToken.isBlank()) {
			throw new PasswordResetException();
		}

		PasswordResetToken token = tokenRepository.findByTokenHash(ResetTokens.hash(rawToken))
			.orElseThrow(PasswordResetException::new);

		OffsetDateTime now = OffsetDateTime.now(clock);
		if (!token.isUsable(now)) {
			throw new PasswordResetException();
		}

		// 비밀번호 정책 검증이 먼저다. 토큰을 소모한 뒤 정책에서 걸리면
		// 사용자는 링크를 잃고 처음부터 다시 해야 한다.
		localAccountService.changePassword(token.getMember(), newPassword);
		token.markUsed(now);

		log.info("Password reset completed: member={}", token.getMember().getId());
		return token.getMember();
	}

	/** 만료 토큰 정리. 스케줄러에서 호출한다. */
	@Transactional
	public int purgeExpired() {
		return tokenRepository.deleteExpiredBefore(OffsetDateTime.now(clock));
	}

	private boolean withinCooldown(Member member, OffsetDateTime now) {
		return tokenRepository.findFirstByMemberIdOrderByCreatedAtDesc(member.getId())
			.map(latest -> latest.getCreatedAt() != null
					&& latest.getCreatedAt().plus(RESEND_COOLDOWN).isAfter(now))
			.orElse(false);
	}

	private void invalidatePrevious(Member member, OffsetDateTime now) {
		tokenRepository.findByMemberIdAndUsedAtIsNull(member.getId())
			.forEach(token -> token.invalidate(now));
	}

	/**
	 * 실패 사유를 담지 않는다. "없는 토큰"과 "만료된 토큰"을 구분해 보여줄 이유가 없고,
	 * 구분하면 토큰 존재 여부를 탐색하는 데 쓰인다.
	 */
	public static class PasswordResetException extends RuntimeException {

		public PasswordResetException() {
			super("링크가 만료되었거나 이미 사용되었습니다. 재설정을 다시 요청해 주세요.");
		}
	}
}
