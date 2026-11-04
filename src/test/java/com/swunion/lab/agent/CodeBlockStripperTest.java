package com.swunion.lab.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CodeBlockStripperTest {

	private static final String P = CodeBlockStripper.PLACEHOLDER;

	@Test
	void replacesFencedBlockWithPlaceholder() {
		assertThat(CodeBlockStripper.strip("설명입니다\n```python\nprint(1)\n```\n끝"))
				.isEqualTo("설명입니다\n[코드는 표시하지 않습니다]\n끝");
	}

	@Test
	void keepsSingleLineInlineCode() {
		assertThat(CodeBlockStripper.strip("`len(x)`를 확인하세요")).isEqualTo("`len(x)`를 확인하세요");
	}

	@Test
	void unterminatedFenceIsRemovedToEnd() {
		assertThat(CodeBlockStripper.strip("이렇게 고치세요\n```\nfor i in range(3):\n    print(i)"))
				.isEqualTo("이렇게 고치세요\n" + P);
	}

	@Test
	void everyBlockIsReplacedIncludingTildeFencesAndLongerFences() {
		String in = "가\n```py\na = 1\n```\n나\n~~~\nb = 2\n~~~\n다\n````\nc = 3\n````\n라";
		assertThat(CodeBlockStripper.strip(in)).isEqualTo("가\n" + P + "\n나\n" + P + "\n다\n" + P + "\n라");
	}

	@Test
	void inlineCodeSpanningLinesIsRemoved() {
		assertThat(CodeBlockStripper.strip("답: `def f(x):\n    return x[0]` 입니다")).isEqualTo("답: " + P + " 입니다");
	}

	@Test
	void nullAndPlainTextPassThrough() {
		assertThat(CodeBlockStripper.strip(null)).isNull();
		assertThat(CodeBlockStripper.strip("코드 없음")).isEqualTo("코드 없음");
	}
}
