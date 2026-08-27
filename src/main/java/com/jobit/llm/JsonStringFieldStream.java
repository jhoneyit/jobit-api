package com.jobit.llm;

import java.util.function.Consumer;

/**
 * 스트리밍 구조화 출력에서 <b>문자열 필드 하나의 내용만</b> 실시간으로 뽑아낸다.
 *
 * <p>구조화 출력(GBNF)은 JSON 전체가 닫혀야 파싱할 수 있어 그대로는 스트리밍이 안 된다.
 * 그렇다고 스키마를 버리면 서버 재검증(refs 대조)의 근거가 사라진다 — 그래서 둘을 다 갖는다:
 * 원문은 호출부가 통째로 모아 끝에서 정식 파싱하고, 이 클래스는 그 원문이 흐르는 동안
 * 지정한 필드의 문자열 내용만 이스케이프를 풀어 조각조각 내보낸다.
 *
 * <p>조각은 JSON 문법 경계를 무시하고 잘려 온다 — {@code \}{@code u123} 처럼 이스케이프
 * 중간에서 끊겨도 상태가 다음 {@link #feed} 로 이어진다. 같은 이유로 인스턴스는 스트림
 * 하나에만 쓴다 (스레드 안전하지 않다).
 *
 * <p><b>필드가 다른 문자열 값 안에 먼저 등장하면 오작동한다.</b> 키 탐색이 문법이 아니라
 * 문자열 매칭이라서다. 대상 필드를 스키마의 첫 프로퍼티로 두면 이 경우가 없다
 * ({@code JsonSchemas} 는 record 컴포넌트 순서를 유지한다).
 */
public final class JsonStringFieldStream {

	private enum State {
		SEEK_KEY, EXPECT_COLON, EXPECT_QUOTE, IN_STRING, ESCAPE, UNICODE, DONE
	}

	private final String keyToken;

	private final Consumer<String> onDelta;

	private State state = State.SEEK_KEY;

	private int keyPos = 0;

	private final StringBuilder unicode = new StringBuilder(4);

	public JsonStringFieldStream(String field, Consumer<String> onDelta) {
		this.keyToken = "\"" + field + "\"";
		this.onDelta = onDelta;
	}

	/** 스트림 조각 하나를 소화한다. 필드 내용이 나오면 이스케이프를 푼 텍스트로 onDelta 를 부른다. */
	public void feed(String chunk) {
		if (state == State.DONE) {
			return;
		}
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < chunk.length() && state != State.DONE; i++) {
			char c = chunk.charAt(i);
			switch (state) {
				case SEEK_KEY -> {
					if (c == keyToken.charAt(keyPos)) {
						if (++keyPos == keyToken.length()) {
							state = State.EXPECT_COLON;
						}
					}
					else {
						keyPos = c == keyToken.charAt(0) ? 1 : 0;
					}
				}
				case EXPECT_COLON -> {
					if (c == ':') {
						state = State.EXPECT_QUOTE;
					}
					else if (!Character.isWhitespace(c)) {
						// "answer" 라는 문자열 값이었다 — 키 탐색으로 되돌아간다.
						state = State.SEEK_KEY;
						keyPos = 0;
					}
				}
				case EXPECT_QUOTE -> {
					if (c == '"') {
						state = State.IN_STRING;
					}
					else if (!Character.isWhitespace(c)) {
						state = State.SEEK_KEY;
						keyPos = 0;
					}
				}
				case IN_STRING -> {
					if (c == '\\') {
						state = State.ESCAPE;
					}
					else if (c == '"') {
						state = State.DONE;
					}
					else {
						out.append(c);
					}
				}
				case ESCAPE -> {
					state = State.IN_STRING;
					switch (c) {
						case 'n' -> out.append('\n');
						case 't' -> out.append('\t');
						case 'r' -> out.append('\r');
						case 'b' -> out.append('\b');
						case 'f' -> out.append('\f');
						case 'u' -> {
							unicode.setLength(0);
							state = State.UNICODE;
						}
						// \" \\ \/ — 그 문자 그대로다.
						default -> out.append(c);
					}
				}
				case UNICODE -> {
					unicode.append(c);
					if (unicode.length() == 4) {
						// 서로게이트 쌍은 유니코드 이스케이프 두 번으로 오고, char 둘을 이어 붙이면 복원된다.
						out.append((char) Integer.parseInt(unicode.toString(), 16));
						state = State.IN_STRING;
					}
				}
				case DONE -> {
					// 루프 조건이 막는다.
				}
			}
		}
		if (!out.isEmpty()) {
			onDelta.accept(out.toString());
		}
	}
}
