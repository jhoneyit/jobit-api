package com.jobit.video;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 외부 도구 실행 (yt-dlp · whisper-cli 공용).
 *
 * <p><b>stdout 을 파일로 보낸다.</b> yt-dlp 의 -J 출력이 수 MB 라 파이프 버퍼를 그대로 읽으면
 * 데드락 위험이 있다 — 파일로 리다이렉트하고 stderr 만 메모리로 모은다(진단용 꼬리).
 */
final class Subprocess {

	private Subprocess() {
	}

	/** @return stderr 의 마지막 부분 (성공 시 진단 로그, 실패 시 예외 메시지 재료) */
	static String run(List<String> command, Path workDir, Path stdoutFile, Duration timeout)
			throws IOException, InterruptedException {

		ProcessBuilder builder = new ProcessBuilder(command).directory(workDir.toFile());
		if (stdoutFile != null) {
			builder.redirectOutput(stdoutFile.toFile());
		}
		else {
			builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
		}
		Process process = builder.start();

		// stderr 는 작다 — 진행 로그 수준. 파이프가 차서 프로세스가 멈추지 않게 스레드로 소비한다.
		StringBuilder stderr = new StringBuilder();
		Thread drainer = Thread.ofVirtual().start(() -> {
			try (var reader = process.errorReader(StandardCharsets.UTF_8)) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (stderr.length() < 8_000) {
						stderr.append(line).append('\n');
					}
				}
			}
			catch (IOException ignored) {
				// 프로세스 종료로 스트림이 닫힌 것 — 정상 경로다.
			}
		});

		if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
			process.destroyForcibly();
			throw new IOException("외부 도구가 제한 시간(" + timeout.toSeconds() + "초)을 넘겼습니다: "
					+ command.getFirst());
		}
		drainer.join(Duration.ofSeconds(5));

		if (process.exitValue() != 0) {
			String tail = stderr.length() > 1_500 ? stderr.substring(stderr.length() - 1_500)
					: stderr.toString();
			throw new IOException(command.getFirst() + " 실패 (exit=" + process.exitValue() + "): "
					+ tail.strip());
		}
		return stderr.toString();
	}
}
