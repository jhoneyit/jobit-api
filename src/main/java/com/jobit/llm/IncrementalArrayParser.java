package com.jobit.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 구조화 출력 스트림에서 배열 원소가 완성될 때마다 하나씩 뽑아내는 증분 파서.
 *
 * <p><b>왜 필요한가</b> (스펙 §6 "SSE 스트리밍"): 구조화 출력을 쓰면 모델은
 * {@code {"questions":[{...},{...}]}} 형태의 JSON을 토큰 단위로 흘려보낸다. 전부 받고 나서
 * 한 번에 파싱하면 스트리밍의 의미가 없다 — 사용자는 십수 초를 빈 화면으로 본다. 그래서 흘러오는
 * 텍스트를 지켜보다가 배열 원소 하나가 {@code }} 로 닫히는 순간 그 조각만 파싱해 바로 내보낸다.
 *
 * <p><b>중괄호만 세면 안 된다.</b> 토큰 조각은 문자열 한가운데서 잘려 들어오고, 질문 본문에도
 * 중괄호나 따옴표가 들어갈 수 있다. 그래서 문자열 안인지와 직전 문자가 이스케이프였는지를 함께
 * 추적하는 상태 기계로 짠다.
 *
 * <p>이 클래스는 <b>스레드 안전하지 않다.</b> 스트림 하나당 인스턴스 하나를 쓴다.
 */
@Slf4j
public final class IncrementalArrayParser {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** {@code {"questions": [...]}} 의 {@code questions} 처럼, 배열이 달려 있는 키 이름. */
	private final String arrayKey;

	private final StringBuilder buffer = new StringBuilder();

	private int cursor;

	private boolean arrayStarted;

	private boolean arrayEnded;

	/** 중첩 깊이. 0에서 {@code {} 를 만나면 원소 시작, 0으로 돌아오면 원소 끝. */
	private int depth;

	/** 현재 원소가 시작된 위치. 원소 밖이면 -1. */
	private int objectStart = -1;

	private boolean inString;

	private boolean escaped;

	public IncrementalArrayParser(String arrayKey) {
		this.arrayKey = arrayKey;
	}

	/**
	 * 새 텍스트 조각을 밀어 넣고, 이번에 <b>완성된</b> 원소들을 돌려준다.
	 *
	 * @return 완성된 원소가 없으면 빈 리스트
	 */
	public List<String> push(String chunk) {
		buffer.append(chunk);
		List<String> completed = new ArrayList<>();

		if (!arrayStarted && !locateArrayStart()) {
			return completed;
		}

		while (!arrayEnded && cursor < buffer.length()) {
			char ch = buffer.charAt(cursor);

			if (inString) {
				if (escaped) {
					escaped = false;
				}
				else if (ch == '\\') {
					escaped = true;
				}
				else if (ch == '"') {
					inString = false;
				}
				cursor++;
				continue;
			}

			switch (ch) {
				case '"' -> inString = true;
				case '{' -> {
					if (depth == 0) {
						objectStart = cursor;
					}
					depth++;
				}
				case '}' -> {
					depth--;
					if (depth == 0 && objectStart >= 0) {
						completed.add(buffer.substring(objectStart, cursor + 1));
						objectStart = -1;
					}
				}
				case ']' -> {
					if (depth == 0) {
						arrayEnded = true;
					}
				}
				default -> {
					// 배열 원소 밖의 공백·쉼표는 그냥 지나간다.
				}
			}

			cursor++;
		}

		return completed;
	}

	/**
	 * 완성된 원소 JSON을 객체로 바꾼다.
	 *
	 * <p><b>실패해도 스트림을 끊지 않는다.</b> 원소 하나가 스키마에 어긋나도 나머지 질문은 여전히
	 * 쓸 만하다. 그 하나만 버리고 계속 간다.
	 *
	 * @return 파싱에 실패하면 {@code null}
	 */
	public static <T> T read(String json, Class<T> type) {
		try {
			return MAPPER.readValue(json, type);
		}
		catch (JsonProcessingException ex) {
			log.warn("배열 원소 1개를 버립니다 (스키마 불일치): {}", ex.getOriginalMessage());
			return null;
		}
	}

	/** 스트림이 끝났는데 배열이 닫히지 않았다면 잘린 것이다 ({@code max_tokens} 초과 등). */
	public boolean isTruncated() {
		return arrayStarted && !arrayEnded;
	}

	/** 아무것도 못 뽑았을 때 원인을 보려고 남겨 둔다. */
	public String rawBuffer() {
		return buffer.toString();
	}

	/** {@code "questions"} 키 뒤의 여는 대괄호를 찾아 커서를 그 다음으로 옮긴다. */
	private boolean locateArrayStart() {
		int keyIndex = buffer.indexOf("\"" + arrayKey + "\"");
		if (keyIndex < 0) {
			return false;
		}
		int bracket = buffer.indexOf("[", keyIndex);
		if (bracket < 0) {
			return false;
		}
		cursor = bracket + 1;
		arrayStarted = true;
		return true;
	}
}
