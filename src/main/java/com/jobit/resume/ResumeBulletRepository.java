package com.jobit.resume;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResumeBulletRepository extends JpaRepository<ResumeBullet, UUID> {

	List<ResumeBullet> findByResumeIdOrderBySortOrder(UUID resumeId);

	/**
	 * 이력서별 문장 수를 <b>한 번에</b> 센다.
	 *
	 * <p>목록 화면이 줄마다 "문장 N개"를 보여주는데, 이력서마다 따로 세면 그대로 N+1 이다.
	 * {@code JdSubmissionService} 가 집계 셋을 배치로 모으는 것과 같은 이유다.
	 *
	 * <p><b>문장이 0개인 이력서는 결과에 없다</b> — {@code group by} 라 행 자체가 나오지 않는다.
	 * 호출부가 0으로 채워야 한다.
	 */
	@Query("""
			select b.resume.id as resumeId, count(b) as count
			from ResumeBullet b
			where b.resume.id in :resumeIds
			group by b.resume.id
			""")
	List<BulletCount> countByResumeIds(@Param("resumeIds") Collection<UUID> resumeIds);

	/** 위 집계의 투영. */
	interface BulletCount {

		UUID getResumeId();

		long getCount();
	}
}
