package com.jobit.question;

import com.jobit.jd.Requirement;
import com.jobit.jd.RequirementEmbeddingRepository;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 질문 은행에서 참고 질문을 추린다 (스펙 §5 5단계 — 유사 공고 질문 추천 RAG).
 *
 * <p>새 공고의 요구사항마다 <b>다른 공고의 유사 요구사항에서 이미 나온 질문</b>을 찾아
 * 생성 프롬프트에 참고로 싣는다. 은행이 쌓일수록 생성 품질이 오르는 구조이고, LLM 을 더
 * 부르지 않는다 — 검색은 SQL(pgvector) 하나다.
 *
 * <p><b>임계값이 상한보다 중요하다</b> ({@code RequirementEmbeddingRepository} 주석 참고).
 * 은행이 작을 때는 빈 목록이 정상이고, 그 경우 프롬프트는 참고 절 없이 지금까지와 똑같다 —
 * 이 기능은 은행이 비어 있어도 아무것도 나빠지지 않아야 한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QuestionReferences {

	/**
	 * 코사인 유사도 하한. "RDBMS 튜닝 경험" 류의 상투적 요구사항은 공고가 달라도 0.9 안팎으로
	 * 잡히고, 무관한 요구사항은 0.6 아래로 떨어진다 — 0.8 은 "같은 것을 묻는 요구사항"만
	 * 통과시키는 선이다. 실측으로 조정할 값이라 상수로 꺼내 두었다.
	 */
	static final double MIN_SIMILARITY = 0.80;

	/** 요구사항 하나가 끌어올 참고 수. 한 요구사항이 참고 절을 독식하지 않게 한다. */
	static final int PER_REQUIREMENT = 2;

	/**
	 * 참고 전체 상한. 참고는 기준이지 재료가 아니다 — 많이 실을수록 모델이 베끼는 쪽으로
	 * 기울고, 입력 토큰은 {@code num_ctx} 를 출력과 나눠 쓴다.
	 */
	static final int TOTAL_CAP = 8;

	private final RequirementEmbeddingRepository embeddingRepository;

	/** 요구사항 순서대로 훑으며 중복 없이 모은다. 은행에 비슷한 것이 없으면 빈 목록이다. */
	public List<String> collect(List<Requirement> requirements) {
		LinkedHashSet<String> references = new LinkedHashSet<>();

		for (Requirement requirement : requirements) {
			if (references.size() >= TOTAL_CAP) {
				break;
			}
			for (RequirementEmbeddingRepository.BankedQuestion banked : embeddingRepository
				.findSimilarQuestions(requirement.getId(), MIN_SIMILARITY, PER_REQUIREMENT)) {
				references.add(banked.text().strip());
				if (references.size() >= TOTAL_CAP) {
					break;
				}
			}
		}

		if (!references.isEmpty()) {
			log.info("질문 은행 참고 {}개 (요구사항 {}개 기준)", references.size(), requirements.size());
		}
		return List.copyOf(references);
	}
}
