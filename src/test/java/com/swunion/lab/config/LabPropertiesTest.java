package com.swunion.lab.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LabPropertiesTest {

	@Test
	void toStringMasksInstructorPassword() {
		var p = new LabProperties("teacher", "s3cret-Pw!", "claude-haiku-5-5", 15, "http://localhost:8080", "127.0.0.1",
				Path.of("./data"), "b:1", "t/course-", "swunion-s");
		assertThat(p.toString()).doesNotContain("s3cret-Pw!").contains("instructorPassword=***")
				.contains("instructorUsername=teacher").contains("llmModel=claude-haiku-5-5");
	}
}
