package com.swunion.lab.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 학생에게 가는 설명에서 코드 블록을 지운다. 정답 코드 차단을 프롬프트에만 맡기지 않기 위한 서버 쪽 장치다.
 * 한 줄짜리 인라인 코드(`len(x)`)는 "확인할 곳"을 가리키는 데 필요해서 남긴다.
 */
public final class CodeBlockStripper {

	public static final String PLACEHOLDER = "[코드는 표시하지 않습니다]";

	// ``` 또는 ~~~ (3개 이상) 펜스부터 같은 펜스까지. 닫지 않은 펜스는 끝까지 지운다(모델이 잘려도 코드가 새지 않게).
	private static final Pattern FENCE = Pattern.compile("(?s)(`{3,}|~{3,}).*?(?:\\1|\\z)");

	private CodeBlockStripper() {
	}

	public static String strip(String text) {
		if (text == null) {
			return null;
		}
		return stripMultilineInline(FENCE.matcher(text).replaceAll(Matcher.quoteReplacement(PLACEHOLDER)));
	}

	/** 백틱을 앞에서부터 짝지어, 짝 안에 줄바꿈이 있으면 여러 줄 코드로 보고 지운다. */
	private static String stripMultilineInline(String s) {
		StringBuilder out = new StringBuilder(s.length());
		int i = 0;
		while (true) {
			int open = s.indexOf('`', i);
			int close = open < 0 ? -1 : s.indexOf('`', open + 1);
			if (close < 0) {
				break;
			}
			out.append(s, i, open);
			String span = s.substring(open, close + 1);
			out.append(span.indexOf('\n') >= 0 ? PLACEHOLDER : span);
			i = close + 1;
		}
		return out.append(s, i, s.length()).toString();
	}
}
