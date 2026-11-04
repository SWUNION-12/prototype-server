package com.swunion.lab.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.session.SessionRepository;
import com.swunion.lab.session.StudentSession;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CheckSchedulerTest {

	@Test
	void failureOnOneSessionDoesNotStopOthersAndHookSeesSuccesses() {
		Course c = new Course("수업", "3.12", "six==1.16.0");
		var s1 = new StudentSession(c, "a", "t1", "pw");
		var s2 = new StudentSession(c, "b", "t2", "pw");
		var repo = mock(SessionRepository.class);
		when(repo.findByCourse_StatusAndContainerIdIsNotNull(CourseStatus.READY)).thenReturn(List.of(s1, s2));
		var checker = mock(EnvironmentChecker.class);
		var ok = new CheckResult(true, false, List.of(), List.of());
		List<StudentSession> checked = new ArrayList<>();
		when(checker.checkAndRecord(any())).thenAnswer(inv -> {
			StudentSession s = inv.getArgument(0);
			checked.add(s);
			if (s == s1) {
				throw new IllegalStateException("boom");
			}
			return ok;
		});
		List<StudentSession> hooked = new ArrayList<>();
		new CheckScheduler(repo, checker) {
			@Override
			protected void afterCheck(StudentSession session, CheckResult result) {
				hooked.add(session);
			}
		}.runOnce();
		assertThat(checked).containsExactly(s1, s2);
		assertThat(hooked).containsExactly(s2);
	}
}
