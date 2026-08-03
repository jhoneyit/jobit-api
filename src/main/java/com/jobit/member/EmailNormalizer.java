package com.jobit.member;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 이메일 정규화와 형식 검사.
 *
 * <p>정규화한 값이 {@code member.provider_uid}에 들어가 이메일 중복 판정의 기준이 되므로,
 * 가입·로그인 양쪽에서 같은 함수를 통과시켜야 한다. 한쪽만 소문자로 접으면
 * {@code A@b.com}으로 가입한 사용자가 {@code a@b.com}으로 로그인하지 못한다.
 */
public final class EmailNormalizer {

	/**
	 * 완전한 RFC 5322 검사가 아니다. 오타를 거르는 용도이며, 실제 도달 가능 여부는
	 * 이메일 인증으로만 확인된다 (도입 여부는 스펙 §7 미정).
	 */
	private static final Pattern SHAPE = Pattern.compile("^[^\\s@]+@[^\\s@.]+(\\.[^\\s@.]+)+$");

	private static final int MAX_LENGTH = 254;

	private EmailNormalizer() {
	}

	/**
	 * 앞뒤 공백 제거 + 소문자 변환.
	 *
	 * <p>RFC상 local-part는 대소문자를 구분하지만, 실제로 구분하는 메일 서비스가 거의 없고
	 * 구분하면 "가입한 이메일로 로그인이 안 된다"는 문의가 확실히 생긴다. 관행을 따른다.
	 */
	public static String normalize(String email) {
		if (email == null) {
			throw new IllegalArgumentException("email must not be null");
		}
		return email.strip().toLowerCase(Locale.ROOT);
	}

	public static boolean isValid(String email) {
		if (email == null) {
			return false;
		}
		String normalized = normalize(email);
		return normalized.length() <= MAX_LENGTH && SHAPE.matcher(normalized).matches();
	}
}
