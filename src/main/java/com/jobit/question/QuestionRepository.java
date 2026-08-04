package com.jobit.question;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuestionRepository extends JpaRepository<Question, UUID> {

	/** 질문 결과 화면. 어느 요구사항에서 나온 질문인지 함께 보여준다. */
	@Query("""
			select q from Question q
			left join fetch q.requirement
			where q.questionSet.id = :questionSetId
			order by q.sortOrder
			""")
	List<Question> findForDisplay(@Param("questionSetId") UUID questionSetId);
}
