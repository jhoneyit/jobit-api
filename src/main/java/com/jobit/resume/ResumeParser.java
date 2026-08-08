package com.jobit.resume;

import java.util.List;

/**
 * 이력서 본문을 문장(bullet) 단위로 쪼개는 포트 (스펙 §3.3).
 *
 * <p><b>왜 문장 단위인가.</b> 두 가지가 여기에 걸려 있다.
 * <ul>
 *   <li><b>리라이트</b>(§4.4)는 항상 문장 하나씩 처리한다 — 이력서 전체를 LLM 에 보내면 토큰
 *       낭비이고, 사용자가 손대지 않기로 한 부분까지 조용히 바뀐다.
 *   <li><b>갭 분석</b>(§4.3)의 후보 추림 단위가 문장이다. 문단 단위로 임베딩하면 요구사항 하나에
 *       대응하는 근거가 뭉뚱그려져 "어느 문장이 근거인지"를 화면에 표시할 수 없다.
 * </ul>
 *
 * <p>{@code JdParser} 와 같은 이유로 인터페이스다 — 제공자 추상화가 아니라 키 없이도 앱이 뜨게
 * 하기 위한 이음매다 ({@link ResumeParserFallbackConfig}).
 */
public interface ResumeParser {

	ParsedResume parse(String rawText);

	/**
	 * @param bullets 이력서에 나온 순서를 유지한다. 최신순으로 재정렬하지 않는다 — 원문에서
	 *                어느 위치의 문장인지가 사용자에게 그대로 보여야 한다.
	 */
	record ParsedResume(List<ParsedBullet> bullets) {
	}

	/**
	 * @param company 이 문장이 속한 회사·프로젝트. 이력서에 없으면 {@code null}
	 * @param period  재직/수행 기간 원문 표기 (예: {@code "2022.03 ~ 2024.08"}).
	 *                <b>파싱해서 날짜로 만들지 않는다</b> — 표기가 제각각이라 정규화하려다
	 *                틀리느니 원문을 그대로 보여 주는 편이 낫다
	 */
	record ParsedBullet(String company, String period, String text) {
	}
}
