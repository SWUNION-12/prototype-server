package com.swunion.lab.help;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 반복 오류 집계용 유형을 정규식으로 뽑는다. LLM 판단에 의존하지 않는다(spec 5.6). */
public final class ErrorTypeExtractor {

	// 중첩 수량자가 없어 4,000자 입력에서도 선형 시간이다. traceback은 마지막 줄이 최종 오류라 마지막 일치를 쓴다.
	private static final Pattern TYPE = Pattern.compile("\\b([A-Z][A-Za-z]*(?:Error|Exception))\\b");

	private ErrorTypeExtractor() {
	}

	public static Optional<String> extract(String text) {
		if (text == null || text.isEmpty()) {
			return Optional.empty();
		}
		Matcher m = TYPE.matcher(text);
		String last = null;
		while (m.find()) {
			last = m.group(1);
		}
		return Optional.ofNullable(last);
	}
}
