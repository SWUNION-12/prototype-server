package com.swunion.lab.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Anthropic API 키를 환경변수와 .env에서 찾는다. 사용자의 .env에 철자가 틀린
 * ANTROPHIC_API_KEY가 있어서 두 이름을 모두 받는다. 올바른 철자가 항상 우선이다.
 */
@Component
public class ApiKeyResolver {

	private static final List<String> NAMES = List.of("ANTHROPIC_API_KEY", "ANTROPHIC_API_KEY");

	private final Map<String, String> env;
	private final Path dotEnvFile;

	@Autowired
	public ApiKeyResolver() {
		this(System.getenv(), Path.of(".env"));
	}

	public ApiKeyResolver(Map<String, String> env, Path dotEnvFile) {
		this.env = env;
		this.dotEnvFile = dotEnvFile;
	}

	public Optional<String> resolve() {
		Map<String, String> fromFile = readDotEnv();
		for (String name : NAMES) {
			for (Map<String, String> source : List.of(env, fromFile)) {
				String value = source.get(name);
				if (value != null && !value.isBlank()) {
					return Optional.of(value.strip());
				}
			}
		}
		return Optional.empty();
	}

	private Map<String, String> readDotEnv() {
		Map<String, String> result = new HashMap<>();
		if (!Files.isRegularFile(dotEnvFile)) {
			return result;
		}
		List<String> lines;
		try {
			lines = Files.readAllLines(dotEnvFile);
		} catch (IOException e) {
			// 예외 메시지에 파일 내용이 섞일 수 있어 원인은 버리고 빈 결과로 처리한다.
			return result;
		}
		for (String raw : lines) {
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			if (line.startsWith("export ")) {
				line = line.substring(7).strip();
			}
			int eq = line.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			result.put(line.substring(0, eq).strip(), unquote(line.substring(eq + 1).strip()));
		}
		return result;
	}

	private static String unquote(String v) {
		if (v.length() >= 2) {
			char first = v.charAt(0);
			if ((first == '"' || first == '\'') && v.charAt(v.length() - 1) == first) {
				return v.substring(1, v.length() - 1);
			}
		}
		return v;
	}

	@Override
	public String toString() {
		return "ApiKeyResolver[dotEnvFile=" + dotEnvFile + "]";
	}
}
