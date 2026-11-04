package com.swunion.lab.check;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class CheckComparator {

	private CheckComparator() {
	}

	public static CheckResult compare(Map<String, String> baseline, String expectedPython, Observed o) {
		if (!o.running()) {
			// 컨테이너가 없으면 나머지 관찰은 의미가 없다.
			return new CheckResult(false, false, List.of(new Issue(IssueType.IDE_DOWN, "ide", null, null)), List.of());
		}
		boolean ide = o.ideConnections() > 0;
		List<Issue> issues = new ArrayList<>();
		if (o.pythonVersion() == null || o.installed() == null) {
			// 가상환경이 깨졌거나 조회가 안 됨. 패키지 목록이 없으니 MISSING을 쏟아내지 않는다.
			issues.add(new Issue(IssueType.PYTHON_MISMATCH, "python", expectedPython, "error"));
			return new CheckResult(true, ide, issues, List.of());
		}
		if (!pythonMatches(expectedPython, o.pythonVersion())) {
			issues.add(new Issue(IssueType.PYTHON_MISMATCH, "python", expectedPython, o.pythonVersion()));
		}
		Map<String, String> installed = new TreeMap<>();
		o.installed().forEach((k, v) -> installed.put(PackageNames.normalize(k), v));
		Map<String, String> base = new TreeMap<>();
		baseline.forEach((k, v) -> base.put(PackageNames.normalize(k), v));
		base.forEach((name, version) -> {
			String actual = installed.get(name);
			if (actual == null) {
				issues.add(new Issue(IssueType.MISSING, name, version, null));
			}
			else if (!actual.equals(version)) {
				issues.add(new Issue(IssueType.VERSION_MISMATCH, name, version, actual));
			}
		});
		List<String> extras = installed.keySet().stream().filter(n -> !base.containsKey(n)).sorted().toList();
		return new CheckResult(true, ide, List.copyOf(issues), extras);
	}

	/** "3.12"는 3.12.x 전부, "3.12.4"는 그 버전만 맞는 것으로 본다. */
	static boolean pythonMatches(String expected, String actual) {
		return actual.equals(expected) || actual.startsWith(expected + ".");
	}
}
