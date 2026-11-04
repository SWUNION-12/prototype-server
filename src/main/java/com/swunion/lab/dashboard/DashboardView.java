package com.swunion.lab.dashboard;

import java.time.Instant;
import java.util.List;

/**
 * 강사 대시보드 JSON. 학생 행에는 세션 토큰, IDE 비밀번호, 컨테이너 id, 포트, 경로를 넣지 않는다.
 * summary·needsHelpReason·extras처럼 모델이나 학생 환경에서 온 글은 데이터로만 담고, 화면이 텍스트로만 그린다.
 */
public record DashboardView(CourseView course, List<StudentRow> students, List<ErrorCount> repeatedErrors,
		String repeatedErrorsNote) {

	public record CourseView(long id, String name, String pythonVersion, List<String> packages, String status,
			String entryCode, String errorSummary) {
	}

	/** issues는 사람이 읽는 한국어 문장, extras는 스펙에 없는 패키지 이름. */
	public record StudentRow(long sessionId, String name, boolean containerRunning, boolean ideConnected,
			Instant lastSeenAt, String envStatus, List<String> issues, List<String> extras, LocalView local,
			boolean needsHelp, String needsHelpReason, List<ActionView> recentActions, Long startupMillis) {
	}

	/** status는 NONE(보고 없음), DIAGNOSED, OK, FAIL. */
	public record LocalView(String status, String os, String arch, String python, List<String> failed) {
	}

	/** at은 끝난 시각(없으면 시작 시각). tools는 호출한 도구 이름을 부른 순서대로. */
	public record ActionView(Instant at, String trigger, String classification, List<String> tools, String outcome,
			String summary) {
	}

	public record ErrorCount(String errorType, long count, List<String> students) {
	}
}
