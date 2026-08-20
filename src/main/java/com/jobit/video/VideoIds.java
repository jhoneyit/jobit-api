package com.jobit.video;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 유튜브 URL → 11자 영상 ID.
 *
 * <p>ID 가 전역 캐시 키다 (V14) — 같은 영상의 URL 표기가 여럿이라
 * (watch?v= / youtu.be / shorts / embed / live) URL 을 키로 쓰면 캐시가 갈라진다.
 */
public final class VideoIds {

	/** 유튜브 영상 ID 는 [A-Za-z0-9_-] 11자다. 그 이상 긴 문자열의 접두사와 헷갈리지 않게 경계를 건다. */
	private static final Pattern[] PATTERNS = {
			Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})(?:[&#]|$)"),
			Pattern.compile("youtu\\.be/([A-Za-z0-9_-]{11})(?:[?&#]|$)"),
			Pattern.compile("/shorts/([A-Za-z0-9_-]{11})(?:[?&#]|$)"),
			Pattern.compile("/embed/([A-Za-z0-9_-]{11})(?:[?&#]|$)"),
			Pattern.compile("/live/([A-Za-z0-9_-]{11})(?:[?&#]|$)") };

	private VideoIds() {
	}

	/** 지원하는 유튜브 URL 이 아니면 null. 예외가 아닌 이유: 호출부가 사용자 문구로 바꿔야 한다. */
	public static String extract(String url) {
		if (url == null || url.isBlank()) {
			return null;
		}
		String trimmed = url.strip();
		// 스킴 없는 입력("youtu.be/...")도 받는다 — 사용자가 주소창에서 복사하다 흘리는 형태다.
		for (Pattern pattern : PATTERNS) {
			Matcher matcher = pattern.matcher(trimmed);
			if (matcher.find() && trimmed.toLowerCase().contains("youtu")) {
				return matcher.group(1);
			}
		}
		return null;
	}
}
