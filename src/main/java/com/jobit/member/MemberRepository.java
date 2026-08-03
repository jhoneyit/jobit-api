package com.jobit.member;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, UUID> {

	Optional<Member> findByProviderAndProviderUid(Member.Provider provider, String providerUid);

	/** 같은 이메일로 이미 OAuth 가입이 되어 있는지 (스펙 §3.6 계정 통합 미정 관련). */
	boolean existsByProviderNotAndEmail(Member.Provider provider, String email);
}
