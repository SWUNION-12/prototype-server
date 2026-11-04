package com.swunion.lab.check;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public record CheckResult(boolean containerRunning, boolean ideConnected, List<Issue> issues, List<String> extras) {

	public boolean ok() {
		return issues.isEmpty();
	}

	/**
	 * 같은 불일치인지 비교하는 키. 순서와 무관하게 같은 문자열이 나와야 한다.
	 * 이후 태스크가 강사 호출 중복 방지에 저장해 두고 비교하므로 형식을 바꾸지 않는다.
	 */
	public String signature() {
		return issues.stream()
				.sorted(Comparator.comparing((Issue i) -> i.type().name()).thenComparing(i -> Objects.toString(i.name(), "")))
				.map(i -> i.type() + ":" + i.name())
				.collect(Collectors.joining("|"));
	}
}
