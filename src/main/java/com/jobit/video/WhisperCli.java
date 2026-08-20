package com.jobit.video;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * whisper.cpp 래퍼 — 자막 없는 영상의 최후 수단.
 *
 * <p><b>모델 경로가 비어 있으면 STT 는 꺼진 것이다</b> ({@code JOBIT_WHISPER_MODEL}).
 * 자막 있는 영상은 그대로 동작하고, 자막 없는 영상만 명확한 예외가 난다 — 이력서 암호화
 * 키와 같은 "설정 없으면 그 기능만 거부" 패턴이다.
 */
@Component
@Slf4j
public class WhisperCli {

	/** small 모델이 Apple Silicon 에서 실시간의 5~10배 — 1시간 영상 ≈ 6~12분. 여유를 두 배 잡는다. */
	private static final Duration TIMEOUT = Duration.ofMinutes(30);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final String binary;

	private final String modelPath;

	public WhisperCli(@Value("${jobit.video.whisper-path:whisper-cli}") String binary,
			@Value("${jobit.video.whisper-model:}") String modelPath) {
		this.binary = binary;
		this.modelPath = modelPath;
	}

	public boolean enabled() {
		return !modelPath.isBlank();
	}

	public List<TranscriptSegment> transcribe(Path wav, Path workDir)
			throws IOException, InterruptedException {

		if (!enabled()) {
			throw new SttNotConfiguredException();
		}

		try {
			// -l auto: 한국어·영어 영상이 섞여 온다. -oj: JSON 출력 (offsets 포함).
			Subprocess.run(List.of(binary, "-m", modelPath, "-f", wav.toString(), "-l", "auto",
					"-oj", "-of", workDir.resolve("stt").toString()), workDir, null, TIMEOUT);
		}
		catch (IOException ex) {
			if (ex.getMessage() != null && ex.getMessage().contains("No such file")) {
				throw new YtDlp.ToolNotConfiguredException("whisper-cli", binary);
			}
			throw ex;
		}

		JsonNode json = MAPPER.readTree(Files.readString(workDir.resolve("stt.json")));
		List<TranscriptSegment> segments = new ArrayList<>();
		for (JsonNode row : json.path("transcription")) {
			String text = row.path("text").asString("").strip();
			if (!text.isEmpty()) {
				segments.add(new TranscriptSegment(
						row.path("offsets").path("from").asInt(0) / 1000, text));
			}
		}
		return segments;
	}

	/** 모델 미설정 — 자막 없는 영상만 막힌다. 문구가 다음 행동(자막 있는 영상 or 설정)을 가리킨다. */
	public static class SttNotConfiguredException extends IllegalStateException {

		public SttNotConfiguredException() {
			super("Whisper 모델이 설정되지 않았습니다 (JOBIT_WHISPER_MODEL). 자막 있는 영상만 요약할 수 있습니다.");
		}
	}
}
