package com.swunion.lab.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class LabPropertiesTest {

	@Test
	void toStringMasksInstructorPassword() {
		var p = new LabProperties("teacher", "s3cret-Pw!", "claude-haiku-5-5", 15, "http://localhost:8080", "127.0.0.1",
				Path.of("./data"), "b:1", "t/course-", "swunion-s", "http://host.docker.internal:8080");
		assertThat(p.toString()).doesNotContain("s3cret-Pw!").contains("instructorPassword=***")
				.contains("instructorUsername=teacher").contains("llmModel=claude-haiku-5-5");
	}

	@Test
	void serverUrlForContainersDefaultsToHostDockerInternalAndCanBeOverridden() {
		var defaults = new Binder(new MapConfigurationPropertySource(Map.of("lab.instructor-username", "i")))
				.bind("lab", LabProperties.class).get();
		var custom = new Binder(new MapConfigurationPropertySource(
				Map.of("lab.server-url-for-containers", "http://10.0.0.5:9000"))).bind("lab", LabProperties.class).get();

		assertThat(defaults.serverUrlForContainers()).isEqualTo("http://host.docker.internal:8080");
		assertThat(custom.serverUrlForContainers()).isEqualTo("http://10.0.0.5:9000");
		assertThat(custom.toString()).contains("serverUrlForContainers=http://10.0.0.5:9000");
	}
}
