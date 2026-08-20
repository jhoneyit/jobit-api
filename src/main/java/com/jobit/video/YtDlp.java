package com.jobit.video;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * yt-dlp 래퍼 — 메타데이터·자막·오디오.
 *
 * <p><b>영상 파일은 받지 않는다.</b> 자막이 있으면 자막만(수 초), 없으면 오디오만(STT 입력)
 * 받는다. 요약에 화면은 필요 없고, 영상 다운로드는 수백 MB 다.
 *
 * <p><b>자막은 존재하는 트랙을 정확히 하나 골라 요청한다.</b> {@code ko.*,en.*} 같은 패턴을
 * 던졌더니 유튜브의 <b>자동 번역 변형</b>(ko-en 등)까지 매칭됐고, 번역 엔드포인트는 429 를
 * 잘게 던진다 — 실영상 테스트에서 실제로 그렇게 실패했다 (2026-08-20). 어떤 트랙이 있는지는
 * 메타데이터가 이미 알고 있으므로 추측할 이유가 없다.
 *
 * <p>Ollama 와 달리 빈 조건부가 아니다 — 바이너리 유무는 호출 시점에 확인하고
 * {@link ToolNotConfiguredException} 을 던진다. 설치 안내는 CLAUDE.md "영상 요약".
 */
@Component
@Slf4j
public class YtDlp {

	private static final Duration META_TIMEOUT = Duration.ofSeconds(90);

	private static final Duration CAPTION_TIMEOUT = Duration.ofMinutes(3);

	/** 오디오는 영상 길이에 비례한다. 1시간 영상 오디오가 수십 MB — 회선이 느려도 이 안엔 온다. */
	private static final Duration AUDIO_TIMEOUT = Duration.ofMinutes(20);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final String binary;

	public YtDlp(@Value("${jobit.video.yt-dlp-path:yt-dlp}") String binary) {
		this.binary = binary;
	}

	/**
	 * @param durationSec 라이브 예약 등 길이가 없는 영상은 0
	 * @param description 주제 판정 재료다 — 저장하지는 않는다
	 */
	public record Meta(String title, String channel, int durationSec, String description) {
	}

	/** 메타 + 존재하는 자막 트랙 목록. 트랙 선택({@link #chooseTrack})의 입력이다. */
	public record Probe(Meta meta, List<String> manualLangs, List<String> autoLangs) {
	}

	public Probe probe(String videoId, Path workDir) throws IOException, InterruptedException {
		Path out = workDir.resolve("meta.json");
		run(List.of(binary, "-J", "--skip-download", "--no-playlist", url(videoId)), workDir, out,
				META_TIMEOUT);

		JsonNode json = MAPPER.readTree(Files.readString(out));
		return new Probe(
				new Meta(json.path("title").asString(null), json.path("channel").asString(null),
						json.path("duration").asInt(0), json.path("description").asString(null)),
				keys(json.path("subtitles")), keys(json.path("automatic_captions")));
	}

	/**
	 * @param auto 자동 생성 트랙인가 — {@code --write-subs} 와 {@code --write-auto-subs} 가 갈린다
	 */
	public record Track(String lang, boolean auto) {
	}

	/**
	 * 수동 ko > 수동 en > 아무 수동 > 자동 원어(-orig) > 자동 ko > 자동 en.
	 *
	 * <p>자동 번역 변형(원어가 아닌 자동 트랙)을 뒤로 미는 이유: 번역 엔드포인트가 429 를 잘
	 * 던지고, 번역 자막은 원문보다 요약 재료로도 나쁘다. {@code -orig} 가 원어 ASR 트랙이다.
	 */
	static Optional<Track> chooseTrack(Probe probe) {
		List<String> manual = probe.manualLangs();
		for (String preferred : List.of("ko", "en")) {
			if (manual.contains(preferred)) {
				return Optional.of(new Track(preferred, false));
			}
		}
		if (!manual.isEmpty()) {
			return Optional.of(new Track(manual.getFirst(), false));
		}

		List<String> auto = probe.autoLangs();
		Optional<String> orig = auto.stream().filter(lang -> lang.endsWith("-orig")).findFirst();
		if (orig.isPresent()) {
			return Optional.of(new Track(orig.get(), true));
		}
		for (String preferred : List.of("ko", "en")) {
			if (auto.contains(preferred)) {
				return Optional.of(new Track(preferred, true));
			}
		}
		return Optional.empty();
	}

	/**
	 * 자막을 받아 파싱한다. 트랙이 없거나 <b>다운로드가 실패해도 empty 다</b> — 자막 실패는
	 * 요약 실패가 아니라 STT 로 가는 갈림길이다 (429 같은 일시 장애가 실제로 온다).
	 */
	public Optional<List<TranscriptSegment>> captions(String videoId, Path workDir, Probe probe)
			throws InterruptedException {

		Optional<Track> track = chooseTrack(probe);
		if (track.isEmpty()) {
			return Optional.empty();
		}

		String subsFlag = track.get().auto() ? "--write-auto-subs" : "--write-subs";
		try {
			run(List.of(binary, "--skip-download", "--no-playlist", subsFlag, "--sub-langs",
					track.get().lang(), "--sub-format", "json3", "-o", "cap", url(videoId)),
					workDir, null, CAPTION_TIMEOUT);

			Optional<Path> file = pickCaptionFile(workDir);
			if (file.isEmpty()) {
				return Optional.empty();
			}
			List<TranscriptSegment> segments = parseJson3(Files.readString(file.get()));
			return segments.isEmpty() ? Optional.empty() : Optional.of(segments);
		}
		catch (ToolNotConfiguredException ex) {
			throw ex; // 도구 부재는 폴백이 아니라 설정 문제다 — 그대로 올린다.
		}
		catch (IOException ex) {
			log.warn("자막 다운로드 실패 — STT 로 넘어간다: video={} {}", videoId, ex.getMessage());
			return Optional.empty();
		}
	}

	/** 16kHz mono WAV — whisper-cli 가 요구하는 형식이다. */
	public Path downloadAudio(String videoId, Path workDir)
			throws IOException, InterruptedException {

		run(List.of(binary, "--no-playlist", "-f", "bestaudio", "-x", "--audio-format", "wav",
				"--postprocessor-args", "ffmpeg:-ar 16000 -ac 1", "-o", "audio.%(ext)s",
				url(videoId)), workDir, null, AUDIO_TIMEOUT);

		Path wav = workDir.resolve("audio.wav");
		if (!Files.exists(wav)) {
			throw new IOException("오디오 파일이 만들어지지 않았습니다");
		}
		return wav;
	}

	/**
	 * 프레임 추출용 최저화질 영상 — 한 번 받아 여러 섹션의 프레임을 로컬에서 뽑는다.
	 *
	 * <p>처음에는 {@code --download-sections} 로 섹션 1초씩만 받았는데, 그 경로는 ffmpeg 가
	 * 구글비디오 URL 을 직접 읽다 403 을 맞았다 (실측 2026-08-20). 일반 다운로드 경로는
	 * yt-dlp 가 헤더를 챙겨 안정적이고, 144p 영상은 분당 ~1MB 라 통짜가 오히려 싸다.
	 */
	public Path downloadWorstVideo(String videoId, Path workDir)
			throws IOException, InterruptedException {

		run(List.of(binary, "--no-playlist", "-f", "worstvideo[ext=mp4]/worst[ext=mp4]/worst",
				"-o", "frames-src.%(ext)s", url(videoId)), workDir, null, AUDIO_TIMEOUT);
		try (Stream<Path> files = Files.list(workDir)) {
			return files.filter(f -> f.getFileName().toString().startsWith("frames-src."))
				.findFirst()
				.orElseThrow(() -> new IOException("영상 파일이 만들어지지 않았습니다"));
		}
	}

	/** 받은 영상에서 특정 시각의 프레임 1장. {@code -ss} 를 입력 앞에 둬 시킹이 빠르다. */
	public void extractFrame(Path video, int startSec, Path outputJpg, Path workDir)
			throws IOException, InterruptedException {
		Subprocess.run(List.of("ffmpeg", "-y", "-loglevel", "error", "-ss",
				String.valueOf(startSec), "-i", video.toString(), "-frames:v", "1", "-q:v", "4",
				outputJpg.toString()), workDir, null, Duration.ofSeconds(30));
	}

	private void run(List<String> command, Path workDir, Path stdout, Duration timeout)
			throws IOException, InterruptedException {
		try {
			Subprocess.run(command, workDir, stdout, timeout);
		}
		catch (IOException ex) {
			if (ex.getMessage() != null && ex.getMessage().contains("No such file")) {
				throw new ToolNotConfiguredException("yt-dlp", binary);
			}
			throw ex;
		}
	}

	private static String url(String videoId) {
		return "https://www.youtube.com/watch?v=" + videoId;
	}

	private static List<String> keys(JsonNode node) {
		List<String> keys = new ArrayList<>();
		node.propertyNames().forEach(keys::add);
		return keys;
	}

	private static Optional<Path> pickCaptionFile(Path workDir) throws IOException {
		try (Stream<Path> files = Files.list(workDir)) {
			return files.filter(p -> p.getFileName().toString().endsWith(".json3")).findFirst();
		}
	}

	/**
	 * 유튜브 json3 형식: {@code events[].tStartMs} + {@code events[].segs[].utf8}.
	 *
	 * <p>자동 생성 자막은 단어 단위 이벤트가 섞여 조각이 잘다 — 여기서 합치지 않는다.
	 * 청크 분할이 어차피 이어 붙인다.
	 */
	static List<TranscriptSegment> parseJson3(String json) {
		List<TranscriptSegment> segments = new ArrayList<>();
		JsonNode events = MAPPER.readTree(json).path("events");
		for (JsonNode event : events) {
			JsonNode segs = event.path("segs");
			if (segs.isMissingNode()) {
				continue;
			}
			StringBuilder text = new StringBuilder();
			for (JsonNode seg : segs) {
				text.append(seg.path("utf8").asString(""));
			}
			String line = text.toString().replace('\n', ' ').strip();
			if (!line.isEmpty()) {
				segments.add(new TranscriptSegment(event.path("tStartMs").asInt(0) / 1000, line));
			}
		}
		return segments;
	}

	/** 외부 도구가 설치되지 않았다. 사용자 잘못이 아니므로 5xx 로 나간다. */
	public static class ToolNotConfiguredException extends IllegalStateException {

		public ToolNotConfiguredException(String tool, String path) {
			super(tool + " 을 찾을 수 없습니다 (설정 경로: " + path + "). CLAUDE.md '영상 요약' 참고.");
		}
	}
}
