package com.swunion.lab.session;

import com.swunion.lab.help.HelpRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 학생 화면(GET /api/s/{token})과 IDE 패널(GET /api/ide/status)이 함께 쓰는 상태 응답의 공통 부분.
 * 학생 화면은 여기에 IDE 주소와 비밀번호를 더하고, IDE 패널은 그대로 쓴다. 토큰, 컨테이너 키, containerId,
 * 포트는 싣지 않는다. 호출자가 응답에 더 얹을 수 있도록 바꿀 수 있는 맵을 돌려준다.
 */
public final class StudentStatusView {

	private StudentStatusView() {
	}

	/**
	 * @param issues 점검이 저장한 불일치 원본(JSON 객체 목록)
	 * @param issuesText issues를 대시보드와 같은 한국어 문구로 푼 것
	 * @param latestHelp 가장 최근 도움 요청. 없으면 null
	 */
	public static Map<String, Object> of(StudentSession s, List<Object> issues, List<String> issuesText,
			HelpRequest latestHelp) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", s.getDisplayName());
		m.put("courseName", s.getCourse().getName());
		// 화면이 수업 종료를 알아채 종료 화면으로 바꾼다.
		m.put("courseStatus", s.getCourse().getStatus());
		m.put("envStatus", s.getEnvStatus());
		m.put("issues", issues);
		m.put("issuesText", issuesText);
		m.put("needsHelp", s.isNeedsHelp());
		// 볼륨이 사라져 빈 작업 공간으로 다시 만들었을 때 화면이 안내한다.
		m.put("workspaceLost", s.getWorkspaceLostAt() != null);
		m.put("latestHelp", latestHelp(latestHelp));
		return m;
	}

	// errorText는 돌려주지 않는다. 응답을 작게 유지한다.
	private static Map<String, Object> latestHelp(HelpRequest h) {
		if (h == null) {
			return null;
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", h.getId());
		m.put("status", h.getStatus());
		m.put("errorType", h.getErrorType());
		m.put("explanation", h.getExplanation());
		// 이 기능 전의 행과 처리 중인 요청은 null이다.
		m.put("classification", h.getClassification());
		m.put("escalated", h.isEscalated());
		m.put("createdAt", h.getCreatedAt());
		return m;
	}
}
