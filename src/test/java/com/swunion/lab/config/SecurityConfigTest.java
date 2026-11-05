package com.swunion.lab.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

@ExtendWith(OutputCaptureExtension.class)
class SecurityConfigTest {

	static final String WARNING = "학생에게 IDE를 주기 전에 INSTRUCTOR_PASSWORD를 바꾸세요";

	private static LabProperties props(String password) {
		return new LabProperties("instructor", password, "m", 15, "http://localhost:8080", "127.0.0.1", Path.of("."),
				"b:1", "t/course-", "swunion-s", "http://host.docker.internal:8080");
	}

	@Test
	void defaultPasswordLogsWarning(CapturedOutput out) {
		new SecurityConfig().userDetailsService(props("instructor"),
				PasswordEncoderFactories.createDelegatingPasswordEncoder());
		assertThat(out.getAll()).contains("WARN").contains(WARNING);
	}

	@Test
	void changedPasswordLogsNothing(CapturedOutput out) {
		new SecurityConfig().userDetailsService(props("Changed-Pw-42"),
				PasswordEncoderFactories.createDelegatingPasswordEncoder());
		assertThat(out.getAll()).doesNotContain(WARNING).doesNotContain("Changed-Pw-42");
	}
}
