package com.swunion.lab.local;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 조회 명령 출력(세 줄)을 엄격하게 검사한다. 앞뒤 공백과 빈 줄만 무시하고 나머지는 모두 거부한다.
 * 원문은 어디에도 저장하거나 전달하지 않고 검사를 통과한 값만 돌려준다.
 */
public final class DiagnosticsParser {

	static final int MAX_LENGTH = 2000;
	private static final Pattern OS = Pattern.compile("^os=(macos|windows)$");
	private static final Pattern ARCH_LINE = Pattern.compile("^arch=(.*)$");
	private static final Pattern PYTHON = Pattern.compile("^python=([0-9]+\\.[0-9]+\\.[0-9]+|none)$");
	private static final String FORMAT_HELP = "조회 명령어의 출력 세 줄(os=, arch=, python=)만 그대로 붙여넣어 주세요.";

	private DiagnosticsParser() {
	}

	public static Diagnostics parse(String text, String selectedOs) {
		if (!"macos".equals(selectedOs) && !"windows".equals(selectedOs)) {
			throw new InvalidPasteException("OS는 macOS 또는 Windows 중에서 골라 주세요.");
		}
		if (text == null || text.length() > MAX_LENGTH) {
			throw new InvalidPasteException(FORMAT_HELP);
		}
		List<String> lines = new ArrayList<>();
		for (String raw : text.split("\r?\n|\r")) {
			String t = raw.strip();
			if (!t.isEmpty()) {
				lines.add(t);
			}
		}
		if (lines.size() != 3) {
			throw new InvalidPasteException(FORMAT_HELP + " 다른 줄이 섞여 있거나 줄 수가 맞지 않습니다.");
		}
		Matcher os = OS.matcher(lines.get(0));
		Matcher arch = ARCH_LINE.matcher(lines.get(1));
		Matcher py = PYTHON.matcher(lines.get(2));
		if (!os.matches() || !arch.matches() || !py.matches()) {
			throw new InvalidPasteException(FORMAT_HELP + " 줄 순서와 형식을 확인해 주세요.");
		}
		String archValue = arch.group(1);
		if (!archValue.equals("arm64") && !archValue.equals("x86_64")) {
			throw new InvalidPasteException("지원하지 않는 CPU입니다. arm64 또는 x86_64 컴퓨터에서만 이어갈 수 있습니다.");
		}
		if (!os.group(1).equals(selectedOs)) {
			throw new InvalidPasteException("고른 OS와 붙여넣은 결과의 OS가 다릅니다. 선택한 OS의 명령어를 실행했는지 확인해 주세요.");
		}
		return new Diagnostics(os.group(1), archValue, py.group(1));
	}
}
