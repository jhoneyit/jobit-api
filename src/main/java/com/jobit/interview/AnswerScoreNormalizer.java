package com.jobit.interview;

import java.util.List;
import java.util.TreeSet;

/**
 * 모델이 돌려준 채점 결과를 화면이 믿을 수 있는 형태로 다듬는다
 * (docs/interview-practice-design.md §5 "서버에서 재검증한다").
 *
 * <p>SDK와 분리해 둔 이유는 <b>여기가 이 기능에서 가장 틀리기 쉬운 곳</b>이기 때문이다.
 * 실제 호출 없이 경계값을 전부 태울 수 있어야 한다.
 *
 * <p><b>모델에게 {@code missed}를 묻지 않는다.</b> 설계 문서에는 둘 다 받는 것으로 적었지만,
 * 받아 보면 {@code covered}와 겹치거나 둘을 합쳐도 전체가 안 되는 응답을 걸러내야 한다.
 * {@code covered}의 여집합으로 계산하면 <b>"겹치지 않고 합치면 전체"라는 성질이 계산에서
 * 따라 나온다</b> — 화면이 뼈대 옆에 ✅/❌를 붙일 때 의존하는 성질이 바로 그것이다.
 * 검증할 것을 줄이는 대신 구조에서 없앤다.
 */
final class AnswerScoreNormalizer {

	private AnswerScoreNormalizer() {
	}

	/** 공백을 접은 비교용 형태 — 모델이 인용을 옮기며 줄바꿈·띄어쓰기를 다듬는 것까지 버리면 과하다. */
	private static String collapse(String text) {
		return text == null ? "" : text.replaceAll("\\s+", "");
	}

	/** 인용 최소 길이(공백 제거 후). 너무 짧은 인용은 우연히 일치해 검증이 무의미해진다. */
	private static final int MIN_QUOTE_CHARS = 6;

	/**
	 * 인용 검증 경로 — 모델 응답을 답변 원문과 대조한다 (주입 방어 2층, 2026-08-20).
	 *
	 * <p>인덱스마다 답변에서 그대로 옮긴 인용(quote)이 와야 하고, <b>답변에 없는 인용이 달린
	 * 항목은 버린다</b>. 프롬프트 가드(1층)는 모델 순응에 기대지만 이 대조는 그렇지 않다 —
	 * 주입 답변에는 기술 내용이 없어 인용을 만들 수 없고, covered 가 전부 떨어지면 아래
	 * 점수 상한이 0 이 된다.
	 *
	 * @param transcript 지원자 답변 원문. 인용 대조의 기준이다
	 */
	static AnswerScorer.Score normalize(int rawScore,
			List<AnswerScoreResponse.Covered> rawCovered, int outlineSize, String feedback,
			String transcript) {

		String haystack = collapse(transcript);
		List<Integer> verified = new java.util.ArrayList<>();
		if (rawCovered != null) {
			for (AnswerScoreResponse.Covered item : rawCovered) {
				if (item == null) {
					continue;
				}
				String needle = collapse(item.quote());
				if (needle.length() >= MIN_QUOTE_CHARS && haystack.contains(needle)) {
					verified.add(item.index());
				}
			}
		}
		return normalize(rawScore, verified, outlineSize, feedback);
	}

	/**
	 * 재정규화 경로 — 이미 검증된 인덱스 목록을 받아 성질(범위·중복·상한)만 다시 지킨다.
	 * 저장 직전의 이중 방어({@code InterviewService})가 이 갈래를 쓴다. 멱등이다.
	 *
	 * @param rawCovered 모델이 준 인덱스. null·범위 밖·중복이 섞여 들어온다고 가정한다
	 * @param outlineSize 답변 뼈대 항목 수. 인덱스의 유효 범위는 {@code [0, outlineSize)}
	 * @throws IllegalArgumentException {@code outlineSize}가 0 이하면. 뼈대 없는 질문은
	 *                                  채점 기준이 없으므로 애초에 여기까지 오면 안 된다
	 */
	static AnswerScorer.Score normalize(int rawScore, List<Integer> rawCovered, int outlineSize,
			String feedback) {

		if (outlineSize <= 0) {
			throw new IllegalArgumentException("answerOutline is empty — 채점 기준이 없다");
		}

		// TreeSet 이라 정렬과 중복 제거가 함께 끝난다. 순서가 뒤죽박죽이면 화면이 뼈대와
		// 짝을 맞출 때 매번 정렬해야 한다.
		TreeSet<Integer> covered = new TreeSet<>();
		if (rawCovered != null) {
			for (Integer index : rawCovered) {
				// **범위 밖 인덱스는 버린다.** 모델은 뼈대가 5개인데 7을 지어내기도 한다.
				if (index != null && index >= 0 && index < outlineSize) {
					covered.add(index);
				}
			}
		}

		List<Integer> missed = new java.util.ArrayList<>();
		for (int i = 0; i < outlineSize; i++) {
			if (!covered.contains(i)) {
				missed.add(i);
			}
		}

		// **점수는 검증된 covered 비율을 넘지 못한다.** "짚은 항목 비율에서 시작한다"는 채점
		// 규칙의 서버측 강제다 — 근거 없이 점수만 높은 응답(주입의 전형)이 여기서 잘린다.
		int cap = 100 * covered.size() / outlineSize;
		return new AnswerScorer.Score(Math.min(clampScore(rawScore), cap), List.copyOf(covered),
				List.copyOf(missed), normalizeFeedback(feedback));
	}

	/**
	 * 점수를 0~100으로 자른다.
	 *
	 * <p>예외로 올리지 않는 이유: 범위를 벗어난 점수는 명백한 실수라 자르면 의도가 보존되고
	 * (120 → 100), 재시도해도 covered 는 이미 쓸 만한데 돈만 더 든다. DB CHECK 제약이
	 * 최종 방어선이므로 여기서 반드시 범위를 맞춰야 한다.
	 */
	private static int clampScore(int rawScore) {
		return Math.clamp(rawScore, 0, 100);
	}

	/** 빈 피드백은 null 로 눕힌다 — 화면이 빈 문자열과 없음을 따로 다루지 않게 한다. */
	private static String normalizeFeedback(String feedback) {
		return (feedback == null || feedback.isBlank()) ? null : feedback.strip();
	}

	/**
	 * 답하지 않은 문항의 결과.
	 *
	 * <p><b>LLM을 부르지 않는다.</b> 채점할 내용이 없는데 호출하면 돈만 나가고, 이건 드문 경우가
	 * 아니라 제한 시간이 있는 이상 정상적으로 자주 일어나는 경로다.
	 */
	static AnswerScorer.Score unanswered(int outlineSize) {
		if (outlineSize <= 0) {
			throw new IllegalArgumentException("answerOutline is empty — 채점 기준이 없다");
		}
		return normalize(0, List.of(), outlineSize, "답변이 없어 채점하지 않았습니다.");
	}
}
