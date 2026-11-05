package com.swunion.lab.session;

/**
 * 강사 대시보드 "AI 조치 보고" 칸에 들어가는 글(도움 필요 이유, 에이전트 조치 요약)의 길이 제한.
 * 서버가 저장 시점에 줄이고, 웹(display.ts latestReport)도 같은 규칙으로 한 번 더 줄인다.
 */
public final class ReportText {

	/** 공백 포함 글자 수 */
	public static final int MAX = 50;

	private ReportText() {
	}

	/** MAX자를 넘으면 앞 MAX-1자 + "…"로 줄인다. 서로게이트 쌍 가운데를 자르지 않는다. null은 null. */
	public static String shorten(String s) {
		if (s == null || s.codePointCount(0, s.length()) <= MAX) {
			return s;
		}
		// 글자 수는 화면에서 세는 대로 코드 포인트로 센다(웹의 Array.from과 같은 기준).
		int end = s.offsetByCodePoints(0, MAX - 1);
		return s.substring(0, end) + "…";
	}
}
