package com.jobit.member;

import com.jobit.resume.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 가입·조회와 익명 데이터 이관 (스펙 §3.6).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemberService {

	private final MemberRepository memberRepository;

	private final ResumeRepository resumeRepository;

	/**
	 * OAuth 콜백에서 호출한다. 최초 로그인이면 가입시킨다.
	 *
	 * <p>일반 회원가입은 {@link LocalAccountService#signup}이 담당한다 — 비밀번호 검증과
	 * 해싱이 얽혀 있어 분리했다.
	 */
	@Transactional
	public Member findOrCreateOauth(Member.Provider provider, String providerUid, String email,
			String nickname) {
		if (provider == Member.Provider.LOCAL) {
			throw new IllegalArgumentException("use LocalAccountService.signup for local signup");
		}
		return memberRepository.findByProviderAndProviderUid(provider, providerUid)
			.orElseGet(() -> memberRepository
				.save(Member.oauth(provider, providerUid, EmailNormalizer.normalize(email), nickname)));
	}

	/**
	 * 익명 세션으로 만든 이력서를 회원 소유로 옮긴다 (스펙 §3.6).
	 *
	 * <p>비회원으로 써보고 가입하는 흐름을 유지하기 위한 것이다. 이관을 빠뜨리면 가입 직후
	 * 자기 데이터가 사라진 것처럼 보인다.
	 *
	 * <p>{@code gap_analysis}는 {@code resume}을 통해 소유자가 정해지므로 따로 옮길 것이 없다.
	 * 이관 트리거를 로그인 즉시로 할지 사용자 확인 후로 할지는 미정이다 (스펙 §7).
	 *
	 * @return 옮겨진 이력서 수
	 */
	@Transactional
	public int transferAnonymousData(String anonymousSessionKey, Member member) {
		if (anonymousSessionKey == null || anonymousSessionKey.isBlank()) {
			return 0;
		}
		if (anonymousSessionKey.equals(member.ownerKey())) {
			return 0;
		}

		int moved = resumeRepository.transferOwnership(anonymousSessionKey, member.ownerKey());
		if (moved > 0) {
			log.info("Transferred {} resume(s) from anonymous session to member {}", moved,
					member.getId());
		}
		return moved;
	}
}
