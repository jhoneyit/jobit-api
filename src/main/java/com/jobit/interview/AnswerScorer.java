package com.jobit.interview;

import java.util.List;

/**
 * 면접 답변 하나를 답변 뼈대와 대조해 채점하는 포트
 * (docs/interview-practice-design.md §5).
 *
 * <p><b>채점 기준을 새로 만들지 않는다.</b> {@code question.answer_outline} — 질문 생성이 이미
 * 만들어 두었고 사용자가 결과 화면에서 본 바로 그 뼈대 — 가 기준이다. 화면엔 A를 보여주고
 * B로 채점하면 점수를 납득할 수 없다.
 *
 * <p>{@code JdParser}와 같은 이유로 인터페이스다 — 제공자 추상화가 아니라, LLM 없이 세션 흐름을
 * 먼저 굴려 보기 위한 이음매다.
 */
public interface AnswerScorer {

	/**
	 * @throws IllegalArgumentException 뼈대가 비어 있으면. 뼈대 없는 질문은 채점 기준이 없다
	 */
	Score score(Request request);

	/**
	 * @param answerOutline 채점 기준. {@link Score#covered()}가 이 목록의 인덱스를 가리킨다
	 * @param requirementText 이 질문이 나온 요구사항. 없을 수 있다 — 일반 CS·컬처핏 질문은
	 *                        특정 요구사항에서 파생되지 않는다 ({@code Question.requirement} 참고)
	 * @param transcript 사용자가 말한 내용. <b>신뢰할 수 없는 입력</b>이라 프롬프트에서 격리한다
	 */
	record Request(String questionText, List<String> answerOutline, String requirementText,
			String transcript) {
	}

	/**
	 * @param covered 짚은 포인트의 {@code answerOutline} 인덱스. 오름차순·중복 없음
	 * @param missed  놓친 포인트. <b>서버가 covered 의 여집합으로 계산한다</b> — 모델에게
	 *                묻지 않는다 ({@link AnswerScoreNormalizer} 참고)
	 */
	record Score(int score, List<Integer> covered, List<Integer> missed, String feedback) {
	}
}
