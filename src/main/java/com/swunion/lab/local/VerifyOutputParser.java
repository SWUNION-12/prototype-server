package com.swunion.lab.local;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * verify.py가 마커 사이에 찍은 블록만 읽는다. 마커 밖의 uv 출력이나 프롬프트 같은 잡음은 무시하고,
 * 블록 안에서는 모르는 키·형식에 어긋난 값·중복 키를 모두 거부한다.
 */
public final class VerifyOutputParser {

	static final String BEGIN = "---SWUNION-VERIFY-BEGIN---";
	static final String END = "---SWUNION-VERIFY-END---";
	static final int MAX_LENGTH = 20000;
	private static final Pattern PYTHON = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+$");
	private static final Pattern PACKAGES = Pattern.compile("^([0-9]{1,4})/([0-9]{1,4})$");
	private static final Pattern FAILED_ITEM = Pattern.compile("^[A-Za-z0-9._-]{1,100}:[A-Za-z]{1,40}$");
	private static final String HELP = "설치가 끝날 때 출력된 ---SWUNION-VERIFY-BEGIN--- 부터 ---SWUNION-VERIFY-END--- 까지를 그대로 붙여넣어 주세요.";

	private VerifyOutputParser() {
	}

	public static VerifyResult parse(String text) {
		if (text == null || text.length() > MAX_LENGTH) {
			throw new InvalidPasteException(HELP);
		}
		List<String> lines = new ArrayList<>();
		for (String raw : text.split("\r?\n|\r")) {
			lines.add(raw.strip());
		}
		int begin = lines.indexOf(BEGIN);
		int end = begin < 0 ? -1 : lines.subList(begin + 1, lines.size()).indexOf(END);
		if (begin < 0 || end < 0) {
			throw new InvalidPasteException(HELP);
		}
		List<String> block = lines.subList(begin + 1, begin + 1 + end);

		Set<String> seen = new HashSet<>();
		String verify = null;
		String python = null;
		int passed = 0;
		int total = 0;
		List<String> failed = new ArrayList<>();
		boolean hasFailed = false;
		for (String line : block) {
			if (line.isEmpty()) {
				continue;
			}
			int eq = line.indexOf('=');
			if (eq <= 0) {
				throw bad("알 수 없는 줄이 있습니다.");
			}
			String key = line.substring(0, eq);
			String value = line.substring(eq + 1);
			if (!seen.add(key)) {
				throw bad("같은 항목이 두 번 나옵니다.");
			}
			switch (key) {
				case "verify" -> {
					if (!value.equals("ok") && !value.equals("fail")) {
						throw bad("verify 값이 올바르지 않습니다.");
					}
					verify = value;
				}
				case "python" -> {
					if (!PYTHON.matcher(value).matches()) {
						throw bad("python 값이 올바르지 않습니다.");
					}
					python = value;
				}
				case "packages" -> {
					Matcher m = PACKAGES.matcher(value);
					if (!m.matches() || Integer.parseInt(m.group(1)) > Integer.parseInt(m.group(2))) {
						throw bad("packages 값이 올바르지 않습니다.");
					}
					passed = Integer.parseInt(m.group(1));
					total = Integer.parseInt(m.group(2));
				}
				case "failed" -> {
					hasFailed = true;
					// failedJson 컬럼(4000자)을 넘기지 않도록 항목 수와 길이를 제한한다.
					if (value.length() > 1500 || value.split(",", -1).length > 50) {
						throw bad("failed 항목이 너무 많습니다.");
					}
					for (String item : value.split(",", -1)) {
						if (!FAILED_ITEM.matcher(item).matches()) {
							throw bad("failed 값이 올바르지 않습니다.");
						}
						failed.add(item);
					}
				}
				default -> throw bad("알 수 없는 항목(" + (key.length() > 20 ? "..." : key) + ")이 있습니다.");
			}
		}
		if (verify == null) {
			throw bad("verify 항목이 없습니다.");
		}
		boolean ok = verify.equals("ok");
		if (ok == hasFailed) {
			throw bad("verify와 failed 항목이 서로 맞지 않습니다.");
		}
		return new VerifyResult(ok, python, passed, total, List.copyOf(failed));
	}

	private static InvalidPasteException bad(String detail) {
		return new InvalidPasteException(HELP + " (" + detail + ")");
	}
}
