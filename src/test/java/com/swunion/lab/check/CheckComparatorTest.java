package com.swunion.lab.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CheckComparatorTest {

	static final Map<String, String> BASE = Map.of("six", "1.16.0", "requests", "2.32.3");

	private CheckResult cmp(String py, Observed o) {
		return CheckComparator.compare(BASE, py, o);
	}

	@Test
	void ok() {
		var r = cmp("3.12", new Observed(true, "3.12.7", Map.of("six", "1.16.0", "requests", "2.32.3"), 1));
		assertThat(r.ok()).isTrue();
		assertThat(r.ideConnected()).isTrue();
	}

	@Test
	void missing() {
		var r = cmp("3.12", new Observed(true, "3.12.7", Map.of("six", "1.16.0"), 0));
		assertThat(r.issues()).containsExactly(new Issue(IssueType.MISSING, "requests", "2.32.3", null));
		assertThat(r.ideConnected()).isFalse();
	}

	@Test
	void version() {
		var r = cmp("3.12", new Observed(true, "3.12.7", Map.of("six", "1.16.0", "requests", "2.31.0"), 0));
		assertThat(r.issues()).containsExactly(new Issue(IssueType.VERSION_MISMATCH, "requests", "2.32.3", "2.31.0"));
	}

	@Test
	void extraOnly() {
		var r = cmp("3.12", new Observed(true, "3.12.7",
				Map.of("six", "1.16.0", "requests", "2.32.3", "rich", "13.0.0", "attrs", "1"), 0));
		assertThat(r.ok()).isTrue();
		assertThat(r.extras()).containsExactly("attrs", "rich");
	}

	@Test
	void normalized() {
		var r = cmp("3.12", new Observed(true, "3.12.7", Map.of("Six", "1.16.0", "Requests", "2.32.3"), 0));
		assertThat(r.ok()).isTrue();
	}

	@Test
	void python() {
		var r = cmp("3.12", new Observed(true, "3.11.9", BASE, 0));
		assertThat(r.issues()).containsExactly(new Issue(IssueType.PYTHON_MISMATCH, "python", "3.12", "3.11.9"));
	}

	@Test
	void exactPython() {
		var r = cmp("3.12.4", new Observed(true, "3.12.7", BASE, 0));
		assertThat(r.issues()).extracting(Issue::type).containsExactly(IssueType.PYTHON_MISMATCH);
		assertThat(cmp("3.12.4", new Observed(true, "3.12.4", BASE, 0)).ok()).isTrue();
		// 접두사 경계: 3.1은 3.12와 다르다
		assertThat(cmp("3.1", new Observed(true, "3.12.4", BASE, 0)).ok()).isFalse();
	}

	@Test
	void brokenVenv() {
		var r = cmp("3.12", new Observed(true, null, null, 0));
		assertThat(r.issues()).containsExactly(new Issue(IssueType.PYTHON_MISMATCH, "python", "3.12", "error"));
	}

	@Test
	void stopped() {
		var r = cmp("3.12", new Observed(false, null, null, 0));
		assertThat(r.issues()).containsExactly(new Issue(IssueType.IDE_DOWN, "ide", null, null));
		assertThat(r.containerRunning()).isFalse();
		assertThat(r.ideConnected()).isFalse();
	}

	@Test
	void signatureIsDeterministic() {
		var r = new CheckResult(true, false, List.of(
				new Issue(IssueType.VERSION_MISMATCH, "requests", "1", "2"),
				new Issue(IssueType.MISSING, "six", "1", null)), List.of());
		assertThat(r.signature()).isEqualTo("MISSING:six|VERSION_MISMATCH:requests");
	}
}
