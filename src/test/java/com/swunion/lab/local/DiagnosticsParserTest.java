package com.swunion.lab.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DiagnosticsParserTest {

	@Test
	void parsesMac() {
		assertThat(DiagnosticsParser.parse("os=macos\narch=arm64\npython=3.12.4\n", "macos"))
				.isEqualTo(new Diagnostics("macos", "arm64", "3.12.4"));
	}

	@Test
	void ignoresBlankLinesWhitespaceAndCrlf() {
		assertThat(DiagnosticsParser.parse("\n  os=windows\r\narch=x86_64\r\npython=none\r\n", "windows"))
				.isEqualTo(new Diagnostics("windows", "x86_64", "none"));
	}

	@Test
	void rejectsExtraLine() {
		assertInvalid("os=macos\narch=arm64\npython=3.12.4\nuser=kim", "macos");
	}

	@Test
	void rejectsPromptLineMixedIn() {
		assertInvalid("$ ./diag\nos=macos\narch=arm64\npython=3.12.4", "macos");
	}

	@Test
	void rejectsOsMismatch() {
		assertThatThrownBy(() -> DiagnosticsParser.parse("os=macos\narch=arm64\npython=3.12.4", "windows"))
				.isInstanceOf(InvalidPasteException.class).hasMessageContaining("OS");
	}

	@Test
	void rejectsInjectedArch() {
		assertInvalid("os=macos\narch=arm64;rm\npython=3.12.4", "macos");
	}

	@Test
	void unsupportedCpuHasSpecificMessage() {
		assertThatThrownBy(() -> DiagnosticsParser.parse("os=windows\narch=x86\npython=none", "windows"))
				.isInstanceOf(InvalidPasteException.class).hasMessageContaining("지원하지 않는 CPU");
	}

	@Test
	void rejectsBadPythonAndMissingLines() {
		assertInvalid("os=macos\narch=arm64\npython=3.12", "macos");
		assertInvalid("os=macos\narch=arm64", "macos");
		assertInvalid("", "macos");
		assertInvalid(null, "macos");
		assertInvalid("os=linux\narch=arm64\npython=none", "linux");
	}

	private static void assertInvalid(String text, String os) {
		assertThatThrownBy(() -> DiagnosticsParser.parse(text, os)).isInstanceOf(InvalidPasteException.class);
	}
}
