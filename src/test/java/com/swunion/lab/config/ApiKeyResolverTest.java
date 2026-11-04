package com.swunion.lab.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiKeyResolverTest {

	@Test
	void prefersCorrectSpelling() {
		var r = new ApiKeyResolver(Map.of("ANTHROPIC_API_KEY", "a", "ANTROPHIC_API_KEY", "b"), Path.of("missing"));
		assertThat(r.resolve()).contains("a");
	}

	@Test
	void fallsBackToMisspelledNameInDotEnv(@TempDir Path d) throws Exception {
		Files.writeString(d.resolve(".env"), "ANTROPHIC_API_KEY=sk-test\n");
		assertThat(new ApiKeyResolver(Map.of(), d.resolve(".env")).resolve()).contains("sk-test");
	}

	@Test
	void emptyWhenNothing() {
		assertThat(new ApiKeyResolver(Map.of(), Path.of("missing")).resolve()).isEmpty();
	}

	@Test
	void parsesCommentsQuotesAndExport(@TempDir Path d) throws Exception {
		Files.writeString(d.resolve(".env"), "# ANTHROPIC_API_KEY=commented\nOTHER=x\nANTHROPIC_API_KEY=\"sk-quoted\"\n");
		assertThat(new ApiKeyResolver(Map.of(), d.resolve(".env")).resolve()).contains("sk-quoted");
	}

	@Test
	void correctSpellingInDotEnvBeatsMisspelledInEnv(@TempDir Path d) throws Exception {
		Files.writeString(d.resolve(".env"), "ANTHROPIC_API_KEY='sk-file'\n");
		assertThat(new ApiKeyResolver(Map.of("ANTROPHIC_API_KEY", "env-typo"), d.resolve(".env")).resolve())
				.contains("sk-file");
	}

	@Test
	void blankValueIsIgnored() {
		assertThat(new ApiKeyResolver(Map.of("ANTHROPIC_API_KEY", "  ", "ANTROPHIC_API_KEY", "b"), Path.of("missing"))
				.resolve()).contains("b");
	}

	@Test
	void toStringDoesNotLeakKey() {
		var r = new ApiKeyResolver(Map.of("ANTHROPIC_API_KEY", "sk-secret"), Path.of("missing"));
		assertThat(r.toString()).doesNotContain("sk-secret");
	}
}
