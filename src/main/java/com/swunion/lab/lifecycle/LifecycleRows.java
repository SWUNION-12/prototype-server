package com.swunion.lab.lifecycle;

import com.swunion.lab.agent.AgentActionRepository;
import com.swunion.lab.check.EnvironmentCheckRepository;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.help.HelpRequestRepository;
import com.swunion.lab.local.LocalInstallRepository;
import com.swunion.lab.session.SessionRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학생·수업 삭제의 DB 쪽. 한 트랜잭션에서 외래 키 순서(에이전트 조치 → 도움 요청 → 점검 기록 → 로컬 설치 → 세션
 * → 수업)로 지운다. 중간에 실패하면 전부 되돌려 반쯤 지운 학생이 남지 않는다.
 * 모두 일괄 삭제 문장이라 큰 JSON 컬럼이 든 행을 읽어 오지 않는다.
 */
@Component
public class LifecycleRows {

	private final AgentActionRepository actions;
	private final HelpRequestRepository helps;
	private final EnvironmentCheckRepository checks;
	private final LocalInstallRepository locals;
	private final SessionRepository sessions;
	private final CourseRepository courses;

	public LifecycleRows(AgentActionRepository actions, HelpRequestRepository helps, EnvironmentCheckRepository checks,
			LocalInstallRepository locals, SessionRepository sessions, CourseRepository courses) {
		this.actions = actions;
		this.helps = helps;
		this.checks = checks;
		this.locals = locals;
		this.sessions = sessions;
		this.courses = courses;
	}

	@Transactional
	public void deleteSessionRows(long sessionId) {
		actions.deleteBySessionId(sessionId);
		helps.deleteBySessionId(sessionId);
		checks.deleteBySessionId(sessionId);
		locals.deleteBySessionId(sessionId);
		sessions.deleteAllByIdInBatch(List.of(sessionId));
	}

	@Transactional
	public void deleteCourseRows(long courseId) {
		actions.deleteByCourseId(courseId);
		helps.deleteByCourseId(courseId);
		checks.deleteByCourseId(courseId);
		locals.deleteByCourseId(courseId);
		sessions.deleteByCourseId(courseId);
		courses.deleteAllByIdInBatch(List.of(courseId));
	}
}
