package com.jobit.jd;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

/**
 * JD 본문 정규화와 캐시 키 생성 (스펙 §4.1 1단계).
 *
 * <p>같은 공고를 서로 다른 사용자가 붙여넣을 때 앞뒤 공백이나 줄바꿈 차이로 캐시가 어긋나면
 * 파싱 비용이 그만큼 늘어난다. 인기 공고일수록 손해가 크다.
 *
 * <p><b>대소문자는 건드리지 않는다.</b> 캐시 적중률은 조금 손해지만, 회사명·기술명 표기가
 * 뭉개지면 파싱 결과 품질이 떨어진다. 적중률이 실제로 문제가 되면 그때 재검토한다.
 */
public final class JdTextNormalizer {

	private JdTextNormalizer() {
	}

	/**
	 * 유니코드 정규화(NFKC), 줄바꿈 통일, 연속 공백 압축, 앞뒤 공백 제거.
	 *
	 * <p>NFKC는 전각 영숫자나 호환 문자를 표준형으로 접는다 — 채용 사이트에서 복사하면 흔히 섞인다.
	 */
	public static String normalize(String rawText) {
		if (rawText == null) {
			throw new IllegalArgumentException("rawText must not be null");
		}
		String normalized = Normalizer.normalize(rawText, Normalizer.Form.NFKC);
		normalized = normalized.replace("\r\n", "\n").replace('\r', '\n');
		// 줄 안의 연속 공백은 하나로, 빈 줄이 여러 개면 하나로 접는다.
		normalized = normalized.replaceAll("[ \\t\\x0B\\f]+", " ");
		normalized = normalized.replaceAll(" *\\n *", "\n");
		normalized = normalized.replaceAll("\\n{2,}", "\n");
		return normalized.strip();
	}

	/** 정규화한 본문의 SHA-256 (소문자 hex). {@code job_posting.content_hash}에 들어간다. */
	public static String contentHash(String rawText) {
		byte[] digest = sha256(normalize(rawText).getBytes(StandardCharsets.UTF_8));
		return HexFormat.of().formatHex(digest);
	}

	private static byte[] sha256(byte[] input) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(input);
		}
		catch (NoSuchAlgorithmException ex) {
			// SHA-256은 모든 JVM 구현이 제공한다. 여기 오면 런타임이 깨진 것이다.
			throw new IllegalStateException("SHA-256 not available", ex);
		}
	}
}
