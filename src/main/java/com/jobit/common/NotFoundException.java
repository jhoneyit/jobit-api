package com.jobit.common;

/**
 * 요청한 자원이 없거나, <b>있더라도 요청자의 것이 아닌</b> 경우 (docs/api.md "소유자 검사").
 *
 * <p><b>소유자 불일치를 403이 아니라 이 예외로 처리한다.</b> 403은 "그 자원은 존재한다"를
 * 알려주는 응답이라, ID를 바꿔 가며 넣어 보는 것만으로 남의 이력이 있는지 훑을 수 있다.
 * 개인 자산은 존재 여부 자체가 노출되면 안 되므로 두 경우를 구분하지 않는다.
 *
 * <p>{@link IllegalArgumentException}을 쓰지 않는 이유: 그쪽은 400으로 매핑되어 있어
 * "입력이 틀렸다"는 뜻이 된다. 없는 자원을 지목한 것은 형식 오류가 아니다.
 */
public class NotFoundException extends RuntimeException {

	public NotFoundException(String message) {
		super(message);
	}
}
