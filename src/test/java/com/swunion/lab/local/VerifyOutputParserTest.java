package com.swunion.lab.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class VerifyOutputParserTest {

	private static final String B = "---SWUNION-VERIFY-BEGIN---\n";
	private static final String E = "---SWUNION-VERIFY-END---";

	@Test
	void okWithNoiseAround() {
		VerifyResult r = VerifyOutputParser.parse(
				"Resolved 3 packages\n" + B + "verify=ok\npython=3.12.7\npackages=2/2\n" + E + "\n$ ");
		assertThat(r.ok()).isTrue();
		assertThat(r.python()).isEqualTo("3.12.7");
		assertThat(r.passed()).isEqualTo(2);
		assertThat(r.total()).isEqualTo(2);
		assertThat(r.failed()).isEmpty();
	}

	@Test
	void failWithReasons() {
		VerifyResult r = VerifyOutputParser.parse(
				"x\n" + B + "verify=fail\npython=3.12.7\npackages=1/3\nfailed=numpy:ImportError,six:VersionMismatch\n" + E);
		assertThat(r.ok()).isFalse();
		assertThat(r.failed()).isEqualTo(List.of("numpy:ImportError", "six:VersionMismatch"));
		assertThat(r.passed()).isEqualTo(1);
		assertThat(r.total()).isEqualTo(3);
	}

	@Test
	void installFailureHasNoPythonOrPackages() {
		VerifyResult r = VerifyOutputParser.parse(B + "verify=fail\nfailed=install:UvSyncFailed\n" + E);
		assertThat(r.ok()).isFalse();
		assertThat(r.failed()).containsExactly("install:UvSyncFailed");
		assertThat(r.python()).isNull();
		assertThat(r.total()).isZero();
	}

	@Test
	void toleratesCrlf() {
		VerifyResult r = VerifyOutputParser.parse(
				(B + "verify=ok\npython=3.12.7\npackages=1/1\n" + E).replace("\n", "\r\n"));
		assertThat(r.ok()).isTrue();
	}

	@Test
	void rejectsMissingMarkersAndUnclosedBlock() {
		assertInvalid("verify=ok\npython=3.12.7\npackages=1/1");
		assertInvalid(B + "verify=ok\n");
		assertInvalid(null);
	}

	@Test
	void rejectsUnknownKeyAndMalformedValues() {
		assertInvalid(B + "verify=ok\nuser=kim\n" + E);
		assertInvalid(B + "verify=maybe\n" + E);
		assertInvalid(B + "verify=ok\npython=abc\npackages=1/1\n" + E);
		assertInvalid(B + "verify=ok\npython=3.12.7\npackages=3/2\n" + E);
		assertInvalid(B + "verify=fail\nfailed=numpy:Import Error\n" + E);
		assertInvalid(B + "verify=fail\nfailed=numpy\n" + E);
		assertInvalid(B + "verify=fail\nfailed=" + String.join(",", java.util.Collections.nCopies(51, "a:B")) + "\n" + E);
		assertInvalid(B + "python=3.12.7\n" + E);
		assertInvalid(B + "verify=ok\nverify=ok\n" + E);
		assertInvalid(B + "verify=ok\nfailed=numpy:ImportError\n" + E);
		assertInvalid(B + "verify=fail\n" + E);
		assertInvalid(B + "verify=ok\nsome free text\n" + E);
	}

	private static void assertInvalid(String text) {
		assertThatThrownBy(() -> VerifyOutputParser.parse(text)).isInstanceOf(InvalidPasteException.class);
	}
}
