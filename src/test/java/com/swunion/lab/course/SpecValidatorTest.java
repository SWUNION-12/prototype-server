package com.swunion.lab.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SpecValidatorTest {

	@Test
	void pythonVersion() {
		assertThat(SpecValidator.parsePythonVersion("3.12")).isEqualTo("3.12");
		assertThat(SpecValidator.parsePythonVersion(" 3.12.4 ")).isEqualTo("3.12.4");
		assertThatThrownBy(() -> SpecValidator.parsePythonVersion("2.7")).isInstanceOf(InvalidSpecException.class);
		assertThatThrownBy(() -> SpecValidator.parsePythonVersion("3.12; rm -rf /"))
				.isInstanceOf(InvalidSpecException.class);
		assertThatThrownBy(() -> SpecValidator.parsePythonVersion(null)).isInstanceOf(InvalidSpecException.class);
	}

	@Test
	void packages() {
		assertThat(SpecValidator.parsePackages("numpy==2.1.3\n\npandas\nrequests>=2.31")).containsExactly(
				new PackageSpec("numpy", "==", "2.1.3"), new PackageSpec("pandas", null, null),
				new PackageSpec("requests", ">=", "2.31"));
		assertThatThrownBy(() -> SpecValidator.parsePackages("numpy; import os"))
				.isInstanceOf(InvalidSpecException.class);
		assertThatThrownBy(() -> SpecValidator.parsePackages("git+https://x/y"))
				.isInstanceOf(InvalidSpecException.class);
		assertThatThrownBy(() -> SpecValidator.parsePackages("x\"]\n[evil]")).isInstanceOf(InvalidSpecException.class);
		assertThatThrownBy(() -> SpecValidator.parsePackages("")).isInstanceOf(InvalidSpecException.class);
	}

	@Test
	void rejectsDuplicatesAndTooMany() {
		assertThatThrownBy(() -> SpecValidator.parsePackages("Six\nsix==1.16.0"))
				.isInstanceOf(InvalidSpecException.class);
		String many = java.util.stream.IntStream.range(0, 51).mapToObj(i -> "pkg" + i)
				.collect(java.util.stream.Collectors.joining("\n"));
		assertThatThrownBy(() -> SpecValidator.parsePackages(many)).isInstanceOf(InvalidSpecException.class);
	}
}
