package com.swunion.lab.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReportTextTest {

	@Test
	void nullStaysNull() {
		assertThat(ReportText.shorten(null)).isNull();
	}

	@Test
	void fiftyCharsAreUnchanged() {
		String s = "가".repeat(50);
		assertThat(ReportText.shorten(s)).isSameAs(s);
	}

	@Test
	void fiftyOneCharsBecome49PlusEllipsis() {
		String r = ReportText.shorten("가".repeat(51));
		assertThat(r).isEqualTo("가".repeat(49) + "…").hasSize(50);
	}

	@Test
	void surrogatePairAtTheCutIsNotSplit() {
		// 49번째 글자 자리(인덱스 48)에 이모지가 시작한다. 코드 포인트 기준 50자를 넘으면 앞 49자 + "…".
		String s = "a".repeat(48) + "😀" + "b".repeat(10);
		String r = ReportText.shorten(s);
		assertThat(r).isEqualTo("a".repeat(48) + "😀" + "…");
		assertThat(r.codePointCount(0, r.length())).isEqualTo(50);
		// 50번째 코드 포인트가 이모지여도 51자째가 있으면 그 이모지는 잘려 나가고 깨진 쌍이 남지 않는다.
		String t = "a".repeat(49) + "😀" + "b";
		assertThat(ReportText.shorten(t)).isEqualTo("a".repeat(49) + "…");
		// 50 코드 포인트(UTF-16으로는 51)는 그대로 둔다.
		String u = "a".repeat(49) + "😀";
		assertThat(ReportText.shorten(u)).isEqualTo(u);
	}
}
