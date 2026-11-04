package com.swunion.lab.course;

import com.swunion.lab.check.PackageNames;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 강사 입력을 pyproject.toml에 쓰기 전에 거르는 곳. 허용 문법을 정규식으로 좁혀서
 * TOML 주입, URL/VCS 의존성, 환경 마커가 들어올 틈을 없앤다.
 */
public final class SpecValidator {

	static final int MAX_PACKAGES = 50;
	private static final Pattern PYTHON = Pattern.compile("^3\\.[0-9]{1,2}(\\.[0-9]{1,2})?$");
	private static final String NAME = "[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?";
	private static final String VERSION = "[0-9]+(\\.[0-9]+){0,3}([a-z]+[0-9]*)?";
	private static final Pattern LINE = Pattern.compile("^(" + NAME + ")(?:(==|>=)(" + VERSION + "))?$");

	private SpecValidator() {
	}

	public static String parsePythonVersion(String raw) {
		String v = raw == null ? "" : raw.trim();
		if (!PYTHON.matcher(v).matches()) {
			throw new InvalidSpecException("파이썬 버전은 3.12 또는 3.12.4 형식으로 입력해 주세요.");
		}
		return v;
	}

	public static List<PackageSpec> parsePackages(String multiline) {
		List<PackageSpec> result = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		String text = multiline == null ? "" : multiline;
		for (String rawLine : text.split("\\R")) {
			String line = rawLine.trim();
			if (line.isEmpty()) {
				continue;
			}
			// 오류 메시지에는 입력 줄을 그대로 싣지 않고 길이만 제한해 보여준다.
			Matcher m = LINE.matcher(line);
			if (!m.matches()) {
				throw new InvalidSpecException("패키지 형식이 올바르지 않습니다: '" + shorten(line)
						+ "'. 한 줄에 이름, 이름==버전, 이름>=버전 중 하나만 쓸 수 있습니다.");
			}
			PackageSpec spec = new PackageSpec(m.group(1), m.group(3), m.group(4));
			if (!seen.add(PackageNames.normalize(spec.name()))) {
				throw new InvalidSpecException("같은 패키지가 두 번 적혀 있습니다: " + spec.name());
			}
			result.add(spec);
			if (result.size() > MAX_PACKAGES) {
				throw new InvalidSpecException("패키지는 최대 " + MAX_PACKAGES + "개까지 입력할 수 있습니다.");
			}
		}
		if (result.isEmpty()) {
			throw new InvalidSpecException("패키지를 한 개 이상 입력해 주세요.");
		}
		return result;
	}

	private static String shorten(String s) {
		return s.length() <= 60 ? s : s.substring(0, 60) + "...";
	}
}
