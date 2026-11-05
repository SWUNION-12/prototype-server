package com.swunion.lab.check;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 환경 점검 결과(Issue)를 사람이 읽는 한국어 한 줄로 바꾼다. 강사 대시보드와 IDE 패널이 같은 문구를 보이도록
 * 한 곳에 둔다(실습 화면 /lab도 서버가 풀어 준 이 문장을 그대로 보인다).
 */
public final class IssueTexts {

	private static final Logger log = LoggerFactory.getLogger(IssueTexts.class);

	public static final String UNREADABLE = "점검 결과를 읽을 수 없음";

	private IssueTexts() {
	}

	public static List<String> of(List<Issue> issues) {
		return issues.stream().map(IssueTexts::of).toList();
	}

	public static String of(Issue i) {
		String name = orUnknown(i.name());
		if (i.type() == null) {
			return name;
		}
		return switch (i.type()) {
			case MISSING -> name + " 없음 (기준 " + orUnknown(i.expected()) + ")";
			case VERSION_MISMATCH -> name + " 버전 다름 (" + orUnknown(i.actual()) + ", 기준 " + orUnknown(i.expected())
					+ ")";
			// 가상환경이 깨져 파이썬을 실행하지 못하면 점검이 actual에 "error"를 넣는다.
			case PYTHON_MISMATCH -> "error".equals(i.actual()) ? "파이썬 실행 실패"
					: "파이썬 버전 다름 (" + orUnknown(i.actual()) + ", 기준 " + orUnknown(i.expected()) + ")";
			case IDE_DOWN -> "IDE 중단";
		};
	}

	/**
	 * 세션에 저장된 issuesJson을 문구 목록으로 읽는다. 비어 있으면 빈 목록이고, 읽을 수 없으면 예외 대신
	 * {@link #UNREADABLE} 한 줄을 돌려준다: 깨진 점검 결과 하나가 대시보드나 패널 전체를 막으면 안 된다.
	 *
	 * @param sessionId 읽지 못했을 때 로그에 남기는 용도로만 쓴다
	 */
	public static List<String> fromJson(JsonMapper json, String raw, long sessionId) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		try {
			return of(json.readValue(raw, new TypeReference<List<Issue>>() {
			}));
		}
		catch (RuntimeException e) {
			log.warn("세션 {}의 점검 결과를 읽지 못했습니다: {}", sessionId, e.getMessage());
			return List.of(UNREADABLE);
		}
	}

	private static String orUnknown(String s) {
		return s == null ? "알 수 없음" : s;
	}
}
