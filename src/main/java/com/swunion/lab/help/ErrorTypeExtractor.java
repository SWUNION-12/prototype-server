package com.swunion.lab.help;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 반복 오류 집계용 유형을 정규식으로 뽑는다. LLM 판단에 의존하지 않는다(spec 5.6). */
public final class ErrorTypeExtractor {

	// 중첩 수량자가 없어 4,000자 입력에서도 선형 시간이다. traceback은 마지막 줄이 최종 오류라 마지막 일치를 쓴다.
	private static final Pattern TYPE = Pattern.compile("\\b([A-Z][A-Za-z]*(?:Error|Exception))\\b");
	// 대시보드 집계 키로만 쓴다. 학생이 붙인 아주 긴 토큰이 errorType 컬럼(255자)을 넘어 저장이 실패하지 않게 자른다.
	static final int MAX_TYPE = 60;

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
		if (last != null && last.length() > MAX_TYPE) {
			last = last.substring(0, MAX_TYPE);
		}
		return Optional.ofNullable(last);
	}
}
