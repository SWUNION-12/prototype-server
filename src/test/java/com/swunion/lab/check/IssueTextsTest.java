package com.swunion.lab.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 대시보드와 IDE 패널이 같은 한국어 문구를 쓰게 하는 공유 함수. */
class IssueTextsTest {

	final JsonMapper json = JsonMapper.builder().build();

	@Test
	void formatsEveryIssueType() {
		assertThat(IssueTexts.of(List.of(
				new Issue(IssueType.MISSING, "six", "1.16.0", null),
				new Issue(IssueType.VERSION_MISMATCH, "rich", "13.7.0", "13.6.0"),
				new Issue(IssueType.PYTHON_MISMATCH, "python", "3.12", "3.11.9"),
				new Issue(IssueType.PYTHON_MISMATCH, "python", "3.12", "error"),
				new Issue(IssueType.IDE_DOWN, "ide", null, null)))).containsExactly(
						"six 없음 (기준 1.16.0)",
						"rich 버전 다름 (13.6.0, 기준 13.7.0)",
						"파이썬 버전 다름 (3.11.9, 기준 3.12)",
						"파이썬 실행 실패",
						"IDE 중단");
	}

	@Test
	void missingPartsReadAsUnknownAndNoTypeKeepsTheName() {
		assertThat(IssueTexts.of(List.of(new Issue(IssueType.MISSING, null, null, null),
				new Issue(null, "numpy", null, null)))).containsExactly("알 수 없음 없음 (기준 알 수 없음)", "numpy");
	}

	@Test
	void fromJsonReadsStoredIssues() {
		String raw = json.writeValueAsString(List.of(new Issue(IssueType.MISSING, "six", "1.16.0", null)));

		assertThat(IssueTexts.fromJson(json, raw, 7L)).containsExactly("six 없음 (기준 1.16.0)");
	}

	@Test
	void fromJsonTreatsBlankAsNoIssues() {
		assertThat(IssueTexts.fromJson(json, null, 7L)).isEmpty();
		assertThat(IssueTexts.fromJson(json, "  ", 7L)).isEmpty();
		assertThat(IssueTexts.fromJson(json, "[]", 7L)).isEmpty();
	}

	@Test
	void fromJsonNeverThrowsOnUnreadableJson() {
		assertThat(IssueTexts.fromJson(json, "{깨진", 7L)).containsExactly("점검 결과를 읽을 수 없음");
	}
}
