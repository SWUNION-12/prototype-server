package com.swunion.lab.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swunion.lab.agent.AgentTrigger;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.session.SessionRepository;
import com.swunion.lab.session.StudentSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CheckSchedulerTest {

	static final CheckResult OK = new CheckResult(true, false, List.of(), List.of());
	static final CheckResult MISSING = new CheckResult(true, false,
			List.of(new Issue(IssueType.MISSING, "six", "1.16.0", null)), List.of());

	Course c = new Course("수업", "3.12", "six==1.16.0");
	SessionRepository repo = mock(SessionRepository.class);
	EnvironmentChecker checker = mock(EnvironmentChecker.class);
	AgentTrigger agent = mock(AgentTrigger.class);

	private StudentSession session(long id, String escalatedSignature) {
		var s = new StudentSession(c, "s" + id, "t" + id, "pw");
		ReflectionTestUtils.setField(s, "id", id);
		s.setLastEscalatedSignature(escalatedSignature);
		when(repo.findById(id)).thenReturn(Optional.of(s));
		return s;
	}

	private void sessions(StudentSession... all) {
		when(repo.findByCourse_StatusAndContainerIdIsNotNull(CourseStatus.READY)).thenReturn(List.of(all));
	}

	@Test
	void failureOnOneSessionDoesNotStopOthersAndHookSeesSuccesses() {
		var s1 = session(1, null);
		var s2 = session(2, null);
		sessions(s1, s2);
		List<StudentSession> checked = new ArrayList<>();
		when(checker.checkAndRecord(any())).thenAnswer(inv -> {
			StudentSession s = inv.getArgument(0);
			checked.add(s);
			if (s == s1) {
				throw new IllegalStateException("boom");
			}
			return OK;
		});
		List<StudentSession> hooked = new ArrayList<>();
		new CheckScheduler(repo, checker, agent) {
			@Override
			protected void afterCheck(StudentSession session, CheckResult result) {
				hooked.add(session);
			}
		}.runOnce();
		assertThat(checked).containsExactly(s1, s2);
		assertThat(hooked).containsExactly(s2);
	}

	@Test
	void sessionWithRunningAgentIsNotCheckedAtAll() {
		var s1 = session(1, null);
		var s2 = session(2, null);
		sessions(s1, s2);
		when(agent.isRunning(1L)).thenReturn(true);
		when(checker.checkAndRecord(any())).thenReturn(MISSING);

		new CheckScheduler(repo, checker, agent).runOnce();

		verify(checker, never()).checkAndRecord(s1);
		verify(checker).checkAndRecord(s2);
		verify(agent, never()).onCheckMismatch(org.mockito.ArgumentMatchers.eq(s1), any());
		verify(agent).onCheckMismatch(s2, MISSING);
	}

	@Test
	void mismatchAlreadyEscalatedIsNotSentToAgentAgain() {
		var same = session(1, "MISSING:six");
		var other = session(2, "VERSION_MISMATCH:six");
		sessions(same, other);
		when(checker.checkAndRecord(any())).thenReturn(MISSING);

		new CheckScheduler(repo, checker, agent).runOnce();

		verify(agent, never()).onCheckMismatch(org.mockito.ArgumentMatchers.eq(same), any());
		verify(agent).onCheckMismatch(other, MISSING);
	}

	@Test
	void signatureStoredAfterTickStartIsHonoured() {
		// 틱이 읽은 엔티티에는 서명이 없지만, 그 사이 끝난 실행이 저장했다
		var stale = new StudentSession(c, "s", "t", "pw");
		ReflectionTestUtils.setField(stale, "id", 5L);
		var latest = new StudentSession(c, "s", "t", "pw");
		ReflectionTestUtils.setField(latest, "id", 5L);
		latest.setLastEscalatedSignature("MISSING:six");
		when(repo.findById(5L)).thenReturn(Optional.of(latest));
		sessions(stale);
		when(checker.checkAndRecord(any())).thenReturn(MISSING);

		new CheckScheduler(repo, checker, agent).runOnce();

		verify(agent, never()).onCheckMismatch(any(), any());
	}

	@Test
	void okCheckClearsEscalatedSignatureAndDoesNotCallAgent() {
		var s = session(1, "MISSING:six");
		sessions(s);
		when(checker.checkAndRecord(any())).thenReturn(OK);

		new CheckScheduler(repo, checker, agent).runOnce();

		verify(repo).clearLastEscalatedSignature(1L);
		verify(agent, never()).onCheckMismatch(any(), any());
	}

	@Test
	void mismatchDoesNotClearSignature() {
		var s = session(1, null);
		sessions(s);
		when(checker.checkAndRecord(any())).thenReturn(MISSING);

		new CheckScheduler(repo, checker, agent).runOnce();

		verify(repo, never()).clearLastEscalatedSignature(anyLong());
		verify(agent).onCheckMismatch(s, MISSING);
	}
}
