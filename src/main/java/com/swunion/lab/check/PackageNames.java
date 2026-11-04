package com.swunion.lab.check;

import java.util.Locale;

/** PEP 503 이름 정규화. 기준 목록과 점검 결과가 같은 키로 비교되게 한다. */
public final class PackageNames {

	private PackageNames() {
	}

	public static String normalize(String name) {
		return name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
	}
}
