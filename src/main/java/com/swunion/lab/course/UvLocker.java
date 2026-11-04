package com.swunion.lab.course;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** 호스트의 uv로 lock만 실행한다. 사용자 입력은 인자로 쓰지 않고 pyproject.toml 안에만 있다. */
@Component
public class UvLocker {

	static final Duration TIMEOUT = Duration.ofSeconds(180);
	private static final int TAIL_CHARS = 2000;

	private final String uvExecutable;

	public UvLocker() {
		this("uv");
	}

	public UvLocker(String uvExecutable) {
		this.uvExecutable = uvExecutable;
	}

	public LockResult lock(Path specDir) {
		Path errFile = null;
		try {
			// 파이프 버퍼가 차서 멈추지 않도록 stderr는 임시 파일로 받는다.
			errFile = Files.createTempFile("uv-lock-", ".err");
			Process p = new ProcessBuilder(List.of(uvExecutable, "--color", "never", "--no-progress", "lock"))
					.directory(specDir.toFile())
					.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
					.redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.redirectError(errFile.toFile()).start();
			if (!p.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return new LockResult(false, "uv lock이 " + TIMEOUT.toSeconds() + "초 안에 끝나지 않아 중단했습니다.");
			}
			if (p.exitValue() == 0) {
				return new LockResult(true, null);
			}
			String err = Files.readString(errFile, StandardCharsets.UTF_8).strip();
			if (err.length() > TAIL_CHARS) {
				err = err.substring(err.length() - TAIL_CHARS);
			}
			return new LockResult(false, err.isEmpty() ? "uv lock이 종료 코드 " + p.exitValue() + "로 실패했습니다." : err);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new LockResult(false, "uv lock이 중단되었습니다.");
		}
		catch (IOException e) {
			return new LockResult(false, "uv를 실행하지 못했습니다: " + e.getMessage());
		}
		finally {
			if (errFile != null) {
				try {
					Files.deleteIfExists(errFile);
				}
				catch (IOException ignored) {
				}
			}
		}
	}
}
