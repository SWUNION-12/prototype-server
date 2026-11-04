package com.swunion.lab.check;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PackageNamesTest {

	@Test
	void normalizesPep503() {
		assertThat(PackageNames.normalize("Six")).isEqualTo("six");
		assertThat(PackageNames.normalize("Typing_Extensions")).isEqualTo("typing-extensions");
		assertThat(PackageNames.normalize("zope.interface")).isEqualTo("zope-interface");
		assertThat(PackageNames.normalize("a-_.b")).isEqualTo("a-b");
	}
}
