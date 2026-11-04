package com.swunion.lab.course;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpecFilesTest {

	@Test
	void minorVersionUsesWildcard(@TempDir Path d) throws IOException {
		SpecFiles.write(d, 7, "3.12", List.of(new PackageSpec("six", "==", "1.16.0"), new PackageSpec("rich", null, null)));
		String toml = Files.readString(d.resolve("pyproject.toml"));
		assertThat(toml).contains("requires-python = \"==3.12.*\"").contains("\"six==1.16.0\"").contains("\"rich\"")
				.contains("[tool.uv]").contains("package = false");
		assertThat(Files.readString(d.resolve(".python-version")).trim()).isEqualTo("3.12");
	}

	@Test
	void patchVersionIsExact(@TempDir Path d) throws IOException {
		SpecFiles.write(d, 7, "3.12.4", List.of(new PackageSpec("six", null, null)));
		assertThat(Files.readString(d.resolve("pyproject.toml"))).contains("requires-python = \"==3.12.4\"");
		assertThat(Files.readString(d.resolve(".python-version")).trim()).isEqualTo("3.12.4");
	}
}
