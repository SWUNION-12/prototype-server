package com.swunion.lab.help;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ErrorTypeExtractorTest {

	@Test
	void takesLastErrorOfTraceback() {
		assertThat(ErrorTypeExtractor.extract(
				"Traceback...\n  File \"a.py\", line 3\nModuleNotFoundError: No module named 'numpyy'"))
				.contains("ModuleNotFoundError");
		assertThat(ErrorTypeExtractor.extract("KeyError: 'a'\nDuring handling...\nValueError: bad"))
				.contains("ValueError");
	}

	@Test
	void emptyWhenNoMatchOrBlank() {
		assertThat(ErrorTypeExtractor.extract("그냥 안 돼요")).isEmpty();
		assertThat(ErrorTypeExtractor.extract(null)).isEmpty();
		assertThat(ErrorTypeExtractor.extract("")).isEmpty();
	}

	@Test
	@Timeout(2)
	void fastOnLongAdversarialInput() {
		assertThat(ErrorTypeExtractor.extract("Aa".repeat(2000))).isEmpty();
		assertThat(ErrorTypeExtractor.extract("E".repeat(4000) + "rro")).isEmpty();
	}
}
