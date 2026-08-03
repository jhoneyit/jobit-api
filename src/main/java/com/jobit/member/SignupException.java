package com.jobit.member;

/**
 * 가입 실패. 사용자에게 그대로 보여줄 수 있는 메시지만 담는다 — 입력값을 메시지에 넣지 않는다.
 */
public class SignupException extends RuntimeException {

	private final Reason reason;

	public SignupException(Reason reason) {
		super(reason.message());
		this.reason = reason;
	}

	public Reason reason() {
		return this.reason;
	}

	public enum Reason {

		INVALID_EMAIL("이메일 형식이 올바르지 않습니다."),
		WEAK_PASSWORD("비밀번호는 8자 이상이어야 합니다."),
		BLANK_NICKNAME("닉네임을 입력해 주세요."),
		EMAIL_TAKEN("이미 가입된 이메일입니다."),
		EMAIL_TAKEN_BY_OAUTH("이미 GitHub 계정으로 가입된 이메일입니다. GitHub으로 로그인해 주세요.");

		private final String message;

		Reason(String message) {
			this.message = message;
		}

		public String message() {
			return this.message;
		}
	}
}
