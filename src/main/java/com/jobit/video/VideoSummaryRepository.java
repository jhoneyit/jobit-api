package com.jobit.video;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoSummaryRepository extends JpaRepository<VideoSummary, UUID> {

	Optional<VideoSummary> findByVideoId(String videoId);

	/** 서버 재시작 복구용 — 워커가 죽으면 RUNNING 이 고아가 된다. */
	List<VideoSummary> findByStatusIn(List<VideoSummary.Status> statuses);
}
