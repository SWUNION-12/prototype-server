package com.swunion.lab.course;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/** 검증을 통과한 값만 받아 uv 프로젝트 파일 두 개를 쓴다. */
public final class SpecFiles {

	private SpecFiles() {
	}

	public static void write(Path dir, long courseId, String pythonVersion, List<PackageSpec> packages) {
		// 3.12 -> ==3.12.* (패치 버전은 uv가 고른다), 3.12.4 -> ==3.12.4
		String requires = pythonVersion.chars().filter(c -> c == '.').count() == 1
				? "==" + pythonVersion + ".*" : "==" + pythonVersion;
		String deps = packages.stream().map(p -> "    \"" + p.toLine() + "\",")
				.collect(Collectors.joining("\n"));
		String toml = """
				[project]
				name = "course-%d"
				version = "0.0.0"
				requires-python = "%s"
				dependencies = [
				%s
				]

				[tool.uv]
				package = false
				""".formatted(courseId, requires, deps);
		try {
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("pyproject.toml"), toml, StandardCharsets.UTF_8);
			Files.writeString(dir.resolve(".python-version"), pythonVersion + "\n", StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
