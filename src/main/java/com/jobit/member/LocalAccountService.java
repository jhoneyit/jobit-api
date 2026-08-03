package com.jobit.member;

import com.jobit.member.SignupException.Reason;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일반 회원가입·로그인 (스펙 §3.6).
 *
 * <p>비밀번호 평문은 이 클래스 밖으로 나가지 않고, 로그에도 남기지 않는다 (스펙 §6).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LocalAccountService {

	/** 하한만 둔다. 복잡도 규칙(특수문자 강제 등)은 오히려 예측 가능한 패턴을 유도한다. */
	static final int MIN_PASSWORD_LENGTH = 8;

	/** BCrypt는 72바이트를 넘는 입력을 잘라낸다. 잘린 채 저장되면 뒷부분이 검증에 쓰이지 않는다. */
	static final int MAX_PASSWORD_BYTES = 72;

	private final MemberRepository memberRepository;

	private final PasswordEncoder passwordEncoder;

	@Transactional
	public Member signup(String email, String rawPassword, String nickname) {
		validate(email, rawPassword, nickname);

		String normalizedEmail = EmailNormalizer.normalize(email);
		rejectIfTaken(normalizedEmail);

		Member member = Member.local(normalizedEmail, passwordEncoder.encode(rawPassword),
				nickname.strip());
		try {
			Member saved = memberRepository.save(member);
			log.info("Local signup: member={}", saved.getId());
			return saved;
		}
		catch (DataIntegrityViolationException ex) {
			// 동시 가입 요청이 먼저 저장했다. 유니크 제약이 최종 방어선이다.
			throw new SignupException(Reason.EMAIL_TAKEN);
		}
	}

	/**
	 * 로그인.
	 *
	 * <p><b>실패 사유를 구분해 반환하지 않는다.</b> "없는 이메일"과 "틀린 비밀번호"를 나누면
	 * 가입 여부가 조회된다 (스펙 §6).
	 *
	 * <p>이메일이 없을 때도 해싱을 한 번 돌린다 — 존재하는 계정만 느리게 응답하면 그 시간차로
	 * 가입 여부를 알 수 있다.
	 */
	@Transactional(readOnly = true)
	public Optional<Member> authenticate(String email, String rawPassword) {
		if (email == null || rawPassword == null) {
			return Optional.empty();
		}

		Optional<Member> found = memberRepository
			.findByProviderAndProviderUid(Member.Provider.LOCAL, EmailNormalizer.normalize(email));

		if (found.isEmpty()) {
			passwordEncoder.encode(rawPassword);
			return Optional.empty();
		}

		Member member = found.get();
		return member.passwordMatches(rawPassword, passwordEncoder::matches)
				? Optional.of(member) : Optional.empty();
	}

	@Transactional
	public void changePassword(Member member, String newRawPassword) {
		validatePassword(newRawPassword);
		member.changePassword(passwordEncoder.encode(newRawPassword));
	}

	private void validate(String email, String rawPassword, String nickname) {
		if (!EmailNormalizer.isValid(email)) {
			throw new SignupException(Reason.INVALID_EMAIL);
		}
		validatePassword(rawPassword);
		if (nickname == null || nickname.isBlank()) {
			throw new SignupException(Reason.BLANK_NICKNAME);
		}
	}

	private void validatePassword(String rawPassword) {
		if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH
				|| rawPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
			throw new SignupException(Reason.WEAK_PASSWORD);
		}
	}

	/**
	 * 같은 이메일의 GitHub 계정이 있으면 그쪽으로 안내한다.
	 *
	 * <p>유니크 제약은 provider별이라 DB는 두 계정을 허용한다. 계정 통합 여부는 미정이며
	 * (스펙 §7), 통합 전까지는 최소한 안내라도 해서 "가입이 왜 두 개냐"는 혼란을 줄인다.
	 */
	private void rejectIfTaken(String normalizedEmail) {
		if (memberRepository.findByProviderAndProviderUid(Member.Provider.LOCAL, normalizedEmail)
			.isPresent()) {
			throw new SignupException(Reason.EMAIL_TAKEN);
		}
		if (memberRepository.existsByProviderNotAndEmail(Member.Provider.LOCAL, normalizedEmail)) {
			throw new SignupException(Reason.EMAIL_TAKEN_BY_OAUTH);
		}
	}
}
