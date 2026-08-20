package com.jobit.video;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 섹션 프레임 저장소 — 보고서의 "중간 캡처".
 *
 * <p>파일 시스템에 둔다 ({@code jobit.video.frames-dir}). DB(jsonb/bytea)에 넣지 않는 이유:
 * 이미지 수십 KB × 섹션 수 × 영상 수가 행 크기를 부풀리고, 서빙은 어차피 바이트 스트림이라
 * 파일이 자연스럽다. 요약이 지워져도 파일은 남는데, 전역 캐시 요약은 지우는 경로가 없어
 * 실질 누수가 아니다 — 생기면 그때 정리 배치를 단다.
 *
 * <p><b>캡처 실패는 요약을 죽이지 않는다.</b> 프레임은 장식이고 보고서가 본체다.
 */
@Component
@Slf4j
public class VideoFrames {

	private final YtDlp ytDlp;

	private final Path baseDir;

	public VideoFrames(YtDlp ytDlp,
			@Value("${jobit.video.frames-dir:${user.home}/.jobit/video-frames}") String baseDir) {
		this.ytDlp = ytDlp;
		this.baseDir = Path.of(baseDir);
	}

	/** 보고서의 각 섹션 시각에서 프레임을 뽑는다. 실패한 섹션은 건너뛴다. */
	public void captureForReport(UUID summaryId, String videoId, VideoReportResponse report) {
		List<Integer> wanted = report.sections().stream()
			.map(VideoReportResponse.Section::startSec)
			.filter(java.util.Objects::nonNull)
			.filter(sec -> !Files.exists(baseDir.resolve(summaryId.toString())
				.resolve(sec + ".jpg")))
			.toList();
		if (wanted.isEmpty()) {
			return;
		}
		try {
			Path dir = baseDir.resolve(summaryId.toString());
			Files.createDirectories(dir);
			Path workDir = Files.createTempDirectory("jobit-frame-" + videoId + "-");
			try {
				Path video = ytDlp.downloadWorstVideo(videoId, workDir);
				for (Integer sec : wanted) {
					try {
						ytDlp.extractFrame(video, sec, dir.resolve(sec + ".jpg"), workDir);
					}
					catch (IOException ex) {
						log.warn("프레임 추출 실패(건너뜀): video={} t={} — {}", videoId, sec,
								ex.getMessage());
					}
				}
				log.info("프레임 캡처 완료: summary={} {}장", summaryId, capturedSeconds(summaryId)
					.size());
			}
			finally {
				try (var files = Files.list(workDir)) {
					for (Path f : files.toList()) {
						Files.deleteIfExists(f);
					}
				}
				Files.deleteIfExists(workDir);
			}
		}
		catch (IOException | InterruptedException ex) {
			log.warn("프레임 캡처를 통째로 건너뛴다: video={} — {}", videoId, ex.toString());
			if (ex instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/** 서빙용 — 파일이 없으면 empty (404 로 나간다). */
	public Optional<Path> resolve(UUID summaryId, int startSec) {
		Path jpg = baseDir.resolve(summaryId.toString()).resolve(startSec + ".jpg");
		return Files.isReadable(jpg) ? Optional.of(jpg) : Optional.empty();
	}

	/** 이 요약에 캡처가 하나라도 있는가 — 화면이 이미지 영역을 그릴지 판단한다. */
	public List<Integer> capturedSeconds(UUID summaryId) {
		Path dir = baseDir.resolve(summaryId.toString());
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (var files = Files.list(dir)) {
			return files.map(f -> f.getFileName().toString())
				.filter(name -> name.endsWith(".jpg"))
				.map(name -> Integer.parseInt(name.substring(0, name.length() - 4)))
				.sorted()
				.toList();
		}
		catch (IOException | NumberFormatException ex) {
			return List.of();
		}
	}
}
