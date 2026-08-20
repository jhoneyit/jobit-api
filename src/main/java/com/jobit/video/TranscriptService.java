package com.jobit.video;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 영상 하나의 대본 확보 — 자막 우선, 없으면 STT.
 *
 * <p>순서가 곧 비용이다: 자막은 수 초, STT 는 오디오 다운로드 + 전사로 영상 길이만큼
 * 걸린다. 자막이 있는데 STT 를 도는 것은 순수 낭비라 여기서 갈림길을 고정한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TranscriptService {

	public enum Source {
		CAPTION, STT
	}

	private final YtDlp ytDlp;

	private final WhisperCli whisperCli;

	public record Result(YtDlp.Meta meta, List<TranscriptSegment> segments, Source source) {
	}

	/** 임시 작업 디렉터리는 성공·실패와 무관하게 지운다 — 오디오 WAV 가 수십 MB 다. */
	public Result acquire(String videoId) throws IOException, InterruptedException {
		Path workDir = Files.createTempDirectory("jobit-video-" + videoId + "-");
		try {
			YtDlp.Probe probe = ytDlp.probe(videoId, workDir);
			YtDlp.Meta meta = probe.meta();

			Optional<List<TranscriptSegment>> captions = ytDlp.captions(videoId, workDir, probe);
			if (captions.isPresent()) {
				log.info("자막 확보: video={} 조각 {}개", videoId, captions.get().size());
				return new Result(meta, captions.get(), Source.CAPTION);
			}

			// 자막이 없다 — STT 가 꺼져 있으면 여기서 명확히 끊는다 (오디오 받기 전에).
			if (!whisperCli.enabled()) {
				throw new WhisperCli.SttNotConfiguredException();
			}
			log.info("자막 없음 — STT 로 전환: video={}", videoId);
			Path wav = ytDlp.downloadAudio(videoId, workDir);
			List<TranscriptSegment> transcribed = whisperCli.transcribe(wav, workDir);
			log.info("STT 완료: video={} 조각 {}개", videoId, transcribed.size());
			return new Result(meta, transcribed, Source.STT);
		}
		finally {
			cleanup(workDir);
		}
	}

	private static void cleanup(Path workDir) {
		try (Stream<Path> files = Files.walk(workDir)) {
			files.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				}
				catch (IOException ignored) {
					// 임시 디렉터리라 OS 가 결국 지운다. 여기서 실패로 요약을 막지 않는다.
				}
			});
		}
		catch (IOException ignored) {
		}
	}
}
