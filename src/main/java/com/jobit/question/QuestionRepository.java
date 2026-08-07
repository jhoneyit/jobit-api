package com.jobit.question;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuestionRepository extends JpaRepository<Question, UUID> {

	/**
	 * 이력 목록의 "질문 10개 / 질문 미생성" 표시용. 페이지에 실린 공고 ID를 한 번에 넘긴다 —
	 * 줄마다 세면 N+1이다.
	 *
	 * <p><b>{@code promptVersion}을 함께 받는 이유.</b> 버전을 올려도 옛 세트는 남는다.
	 * 공고별로 다 세면 합계가 결과 화면에 실제로 뜨는 개수보다 커진다. 게다가 옛 버전만 있는
	 * 공고는 {@code QuestionService}가 어차피 다시 생성하므로 "미생성"이 정직한 표시다.
	 * 즉 이 카운트는 {@code GET /api/questions}가 그 공고에 대해 지금 내놓을 개수와 같아야 한다.
	 *
	 * @return {@code (jobPostingId, count)} 행들. 질문이 없는 공고는 행 자체가 없다
	 */
	@Query("""
			select qs.jobPosting.id, count(q)
			from Question q
			join q.questionSet qs
			where qs.jobPosting.id in :jobPostingIds
			  and qs.promptVersion = :promptVersion
			group by qs.jobPosting.id
			""")
	List<Object[]> countByJobPosting(@Param("jobPostingIds") Collection<UUID> jobPostingIds,
			@Param("promptVersion") String promptVersion);

	/** 질문 결과 화면. 어느 요구사항에서 나온 질문인지 함께 보여준다. */
	@Query("""
			select q from Question q
			left join fetch q.requirement
			where q.questionSet.id = :questionSetId
			order by q.sortOrder
			""")
	List<Question> findForDisplay(@Param("questionSetId") UUID questionSetId);
}
