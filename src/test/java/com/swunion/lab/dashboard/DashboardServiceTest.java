package com.swunion.lab.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swunion.lab.agent.AgentActionFixtures;
import com.swunion.lab.agent.AgentActionRepository;
import com.swunion.lab.agent.Classification;
import com.swunion.lab.agent.Outcome;
import com.swunion.lab.agent.Trigger;
import com.swunion.lab.check.Issue;
import com.swunion.lab.check.IssueType;
import com.swunion.lab.config.LabProperties;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseProvisioner;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.course.CourseService;
import com.swunion.lab.course.CourseStateException;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.dashboard.DashboardView.ActionView;
import com.swunion.lab.dashboard.DashboardView.ErrorCount;
import com.swunion.lab.dashboard.DashboardView.StudentRow;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.docker.DockerOperationException;
import com.swunion.lab.help.HelpRequest;
import com.swunion.lab.help.HelpRequestRepository;
import com.swunion.lab.local.LocalInstall;
import com.swunion.lab.local.LocalInstallRepository;
import com.swunion.lab.local.LocalStatus;
import com.swunion.lab.session.EnvStatus;
import com.swunion.lab.session.JoinException;
import com.swunion.lab.session.SessionRepository;
import com.swunion.lab.session.SessionService;
import com.swunion.lab.session.StudentSession;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest
class DashboardServiceTest {

	@Autowired
	CourseRepository courses;
	@Autowired
	SessionRepository sessions;
	@Autowired
	AgentActionRepository actions;
	@Autowired
	HelpRequestRepository helps;
	@Autowired
	LocalInstallRepository locals;

	JsonMapper json = JsonMapper.builder().build();
	DockerGateway docker = mock(DockerGateway.class);
	DashboardService service;
	Course course;
	int seq;

	@BeforeEach
	void setUp() {
		service = new DashboardService(courses, sessions, actions, helps, locals, json);
		course = newCourse("DASH01", CourseStatus.READY);
	}

	private Course newCourse(String code, CourseStatus status) {
		Course c = new Course("파이썬 기초", "3.12", "six==1.16.0\nrich");
		c.setStatus(status);
		c.setEntryCode(code);
		c.setImageName("t/course-1:1");
		return courses.save(c);
	}

	private StudentSession student(String name) {
		return student(course, name);
	}

	private StudentSession student(Course c, String name) {
		return sessions.save(new StudentSession(c, name, "tok-" + (++seq), "pw-" + seq));
	}

	private DashboardView view() {
		return service.dashboard(course.getId()).orElseThrow();
	}

	private StudentRow row(String name) {
		return view().students().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
	}

	private void help(StudentSession s, String errorType) {
		helps.save(new HelpRequest(s, errorType, errorType == null ? null : errorType + ": x"));
	}

	private static final String TOOL_LOG = "[\"check_environment {} => ok\",\"restore_packages {} => Installed 1 package\","
			+ "\"finish {\\\"classification\\\":\\\"ENV\\\"} => 실행을 끝냅니다.\"]";

	@Test
	void repeatedErrorsCounted() {
		StudentSession a = student("가영");
		StudentSession b = student("나리");
		help(a, "ModuleNotFoundError");
		help(a, "ModuleNotFoundError");
		help(b, "ModuleNotFoundError");
		help(a, "ValueError");

		assertThat(view().repeatedErrors()).containsExactly(
				new ErrorCount("ModuleNotFoundError", 3, List.of("가영", "나리")),
				new ErrorCount("ValueError", 1, List.of("가영")));
		assertThat(view().repeatedErrorsNote()).isEqualTo("도움 버튼을 누른 학생 기준이며 전체 오류 수가 아닙니다.");
	}

	@Test
	void repeatedErrorsTieBreakByTypeIgnoreNoErrorTypeAndOtherCourses() {
		StudentSession a = student("가영");
		StudentSession b = student("나리");
		help(b, "ValueError");
		help(a, "KeyError");
		help(a, null);
		Course other = newCourse("DASH02", CourseStatus.READY);
		help(student(other, "다른반"), "KeyError");

		assertThat(view().repeatedErrors()).containsExactly(
				new ErrorCount("KeyError", 1, List.of("가영")),
				new ErrorCount("ValueError", 1, List.of("나리")));
	}

	@Test
	void recentActionsLimited() {
		StudentSession s = student("가영");
		StudentSession other = student("나리");
		Instant base = Instant.parse("2026-11-04T01:00:00Z");
		for (int i = 1; i <= 7; i++) {
			actions.save(AgentActionFixtures.action(s, i % 2 == 0 ? Trigger.HELP : Trigger.CHECK, Classification.ENV,
					Outcome.RESOLVED, TOOL_LOG, "조치 " + i, base.plus(i, ChronoUnit.MINUTES),
					base.plus(i, ChronoUnit.MINUTES).plusSeconds(20)));
		}
		actions.save(AgentActionFixtures.action(other, Trigger.CHECK, Classification.CODE, Outcome.EXPLAINED, "[]",
				"남의 조치", base, base));

		List<ActionView> recent = row("가영").recentActions();

		assertThat(recent).extracting(ActionView::summary).containsExactly("조치 7", "조치 6", "조치 5", "조치 4", "조치 3");
		ActionView newest = recent.get(0);
		assertThat(newest.at()).isEqualTo(base.plus(7, ChronoUnit.MINUTES).plusSeconds(20));
		assertThat(newest.trigger()).isEqualTo("CHECK");
		assertThat(newest.classification()).isEqualTo("ENV");
		assertThat(newest.outcome()).isEqualTo("RESOLVED");
		assertThat(newest.tools()).containsExactly("check_environment", "restore_packages", "finish");
		assertThat(recent.get(1).trigger()).isEqualTo("HELP");
		assertThat(row("나리").recentActions()).extracting(ActionView::summary).containsExactly("남의 조치");
	}

	@Test
	void actionTimeFallsBackToStartedAtAndBadToolLogGivesNoTools() {
		StudentSession s = student("가영");
		Instant started = Instant.parse("2026-11-04T02:00:00Z");
		actions.save(AgentActionFixtures.action(s, Trigger.CHECK, Classification.UNKNOWN, Outcome.ESCALATED,
				"이건 JSON이 아니다", "실패", started, null));
		actions.save(AgentActionFixtures.action(s, Trigger.CHECK, null, null, null, "기록 없음", started, started));
		actions.save(AgentActionFixtures.action(s, Trigger.HELP, Classification.ENV, Outcome.RESOLVED,
				"{\"tools\":[\"check_environment\"]}", "객체 모양", started, started));
		actions.save(AgentActionFixtures.action(s, Trigger.HELP, Classification.ENV, Outcome.RESOLVED,
				"[\"check_environment {} => ok\",\"… 3건 생략\",42,\"\"]", "생략 표시", started, started));

		List<ActionView> recent = row("가영").recentActions();

		assertThat(recent).extracting(ActionView::summary).containsExactly("생략 표시", "객체 모양", "기록 없음", "실패");
		assertThat(recent.get(0).tools()).as("생략 표시·숫자·빈 문자열은 도구 이름이 아니다").containsExactly("check_environment");
		assertThat(recent.get(1).tools()).isEmpty();
		assertThat(recent.get(2).tools()).isEmpty();
		assertThat(recent.get(2).classification()).isNull();
		assertThat(recent.get(2).outcome()).isNull();
		assertThat(recent.get(3).tools()).isEmpty();
		assertThat(recent.get(3).at()).isEqualTo(started);
	}

	@Test
	void issuesReadable() {
		StudentSession s = student("가영");
		s.setIssuesJson(json.writeValueAsString(List.of(
				new Issue(IssueType.MISSING, "six", "1.16.0", null),
				new Issue(IssueType.VERSION_MISMATCH, "rich", "13.7.0", "13.6.0"),
				new Issue(IssueType.PYTHON_MISMATCH, "python", "3.12", "3.11.9"),
				new Issue(IssueType.PYTHON_MISMATCH, "python", "3.12", "error"),
				new Issue(IssueType.IDE_DOWN, "ide", null, null))));
		s.setExtrasJson(json.writeValueAsString(List.of("numpy", "pandas")));
		s.setEnvStatus(EnvStatus.MISMATCH);
		sessions.save(s);

		StudentRow r = row("가영");

		assertThat(r.issues()).containsExactly(
				"six 없음 (기준 1.16.0)",
				"rich 버전 다름 (13.6.0, 기준 13.7.0)",
				"파이썬 버전 다름 (3.11.9, 기준 3.12)",
				"파이썬 실행 실패",
				"IDE 중단");
		assertThat(r.extras()).containsExactly("numpy", "pandas");
		assertThat(r.envStatus()).isEqualTo("MISMATCH");
	}

	@Test
	void unreadableIssueAndExtrasJsonNeverBreakTheDashboard() {
		StudentSession s = student("가영");
		s.setIssuesJson("{깨진");
		s.setExtrasJson("깨진]");
		sessions.save(s);

		StudentRow r = row("가영");

		assertThat(r.issues()).hasSize(1);
		assertThat(r.extras()).isEmpty();
	}

	@Test
	void studentRowCarriesLiveStateAndCourseView() {
		StudentSession s = student("가영");
		Instant seen = Instant.parse("2026-11-04T03:00:00Z");
		s.setContainerRunning(true);
		s.setIdeConnected(true);
		s.setLastSeenAt(seen);
		s.setStartupMillis(4200L);
		s.setNeedsHelp(true);
		s.setNeedsHelpReason("재점검 실패: MISSING:six");
		sessions.save(s);

		DashboardView v = view();
		StudentRow r = v.students().get(0);

		assertThat(r.sessionId()).isEqualTo(s.getId());
		assertThat(r.containerRunning()).isTrue();
		assertThat(r.ideConnected()).isTrue();
		assertThat(r.lastSeenAt()).isEqualTo(seen);
		assertThat(r.startupMillis()).isEqualTo(4200L);
		assertThat(r.needsHelp()).isTrue();
		assertThat(r.needsHelpReason()).isEqualTo("재점검 실패: MISSING:six");
		assertThat(r.issues()).isEmpty();
		assertThat(r.recentActions()).isEmpty();
		assertThat(v.course().id()).isEqualTo(course.getId());
		assertThat(v.course().name()).isEqualTo("파이썬 기초");
		assertThat(v.course().pythonVersion()).isEqualTo("3.12");
		assertThat(v.course().packages()).containsExactly("six==1.16.0", "rich");
		assertThat(v.course().status()).isEqualTo("READY");
		assertThat(v.course().entryCode()).isEqualTo("DASH01");
		assertThat(v.course().errorSummary()).isNull();
	}

	@Test
	void studentsSortedNeedsHelpFirstThenName() {
		student("다은");
		StudentSession b = student("라온");
		student("가영");
		StudentSession d = student("나리");
		sessions.updateNeedsHelp(b.getId(), true, "x");
		sessions.updateNeedsHelp(d.getId(), true, "y");

		assertThat(view().students()).extracting(StudentRow::name).containsExactly("나리", "라온", "가영", "다은");
	}

	@Test
	void localViewIsNoneUntilReported() {
		student("가영");
		StudentSession ok = student("나리");
		StudentSession fail = student("다은");
		StudentSession diagnosed = student("라온");
		LocalInstall li = new LocalInstall(ok);
		li.setStatus(LocalStatus.OK);
		li.setOs("mac");
		li.setArch("arm64");
		li.setPythonVersion("3.12.4");
		locals.save(li);
		LocalInstall bad = new LocalInstall(fail);
		bad.setStatus(LocalStatus.FAIL);
		bad.setOs("windows");
		bad.setArch("x86_64");
		bad.setFailedJson("[\"numpy\",\"six\"]");
		locals.save(bad);
		LocalInstall d = new LocalInstall(diagnosed);
		d.setStatus(LocalStatus.DIAGNOSED);
		d.setOs("linux");
		locals.save(d);

		assertThat(row("가영").local()).isEqualTo(new DashboardView.LocalView("NONE", null, null, null, List.of()));
		assertThat(row("나리").local())
				.isEqualTo(new DashboardView.LocalView("OK", "mac", "arm64", "3.12.4", List.of()));
		assertThat(row("다은").local())
				.isEqualTo(new DashboardView.LocalView("FAIL", "windows", "x86_64", null, List.of("numpy", "six")));
		assertThat(row("라온").local().status()).isEqualTo("DIAGNOSED");
	}

	@Test
	void unknownCourseIsEmpty() {
		assertThat(service.dashboard(987654L)).isEmpty();
	}

	@Test
	void resolveClearsNeedsHelpButKeepsEscalationSignature() {
		StudentSession s = student("가영");
		sessions.updateEnvStatus(s.getId(), EnvStatus.MISMATCH);
		sessions.updateEscalation(s.getId(), "재점검 실패: MISSING:six", "MISSING:six");

		assertThat(service.resolve(course.getId(), s.getId())).isTrue();

		StudentSession fresh = sessions.findById(s.getId()).orElseThrow();
		assertThat(fresh.isNeedsHelp()).isFalse();
		assertThat(fresh.getNeedsHelpReason()).isNull();
		assertThat(fresh.getLastEscalatedSignature()).as("같은 불일치로 에이전트가 바로 다시 불리지 않게 남긴다")
				.isEqualTo("MISSING:six");
		assertThat(fresh.getEnvStatus()).isEqualTo(EnvStatus.MISMATCH);
	}

	@Test
	void resolveRejectsSessionOfAnotherCourseOrUnknown() {
		Course other = newCourse("DASH02", CourseStatus.READY);
		StudentSession theirs = student(other, "다른반");
		sessions.updateNeedsHelp(theirs.getId(), true, "막힘");

		assertThat(service.resolve(course.getId(), theirs.getId())).isFalse();
		assertThat(service.resolve(course.getId(), 987654L)).isFalse();
		assertThat(service.resolve(987654L, theirs.getId())).isFalse();

		assertThat(sessions.findById(theirs.getId()).orElseThrow().isNeedsHelp()).isTrue();
	}

	// ---- 수업 종료 ----

	private CourseService courseService() {
		return new CourseService(courses, mock(CourseProvisioner.class), r -> {
		}, json, sessions, docker);
	}

	private StudentSession withContainer(String name, String containerId) {
		StudentSession s = student(name);
		s.setContainerId(containerId);
		s.setVolumeName("vol-" + containerId);
		s.setContainerRunning(true);
		s.setIdeConnected(true);
		return sessions.save(s);
	}

	@Test
	void endStopsContainers() {
		withContainer("가영", "c1");
		withContainer("나리", "c2");
		withContainer("다은", "c3");
		student("라온"); // 컨테이너를 만들기 전에 끊긴 세션은 멈출 것이 없다
		CourseService courseService = courseService();

		CourseService.EndResult result = courseService.end(course.getId());

		assertThat(result).isEqualTo(new CourseService.EndResult(3, 0));
		verify(docker).stop("c1");
		verify(docker).stop("c2");
		verify(docker).stop("c3");
		verify(docker, never()).remove(anyString());
		verify(docker, never()).removeVolume(anyString());
		assertThat(courses.findById(course.getId()).orElseThrow().getStatus()).isEqualTo(CourseStatus.ENDED);
		assertThat(sessions.findAll()).filteredOn(s -> s.getContainerId() != null)
				.allSatisfy(s -> assertThat(s.isContainerRunning()).isFalse())
				.allSatisfy(s -> assertThat(s.isIdeConnected()).isFalse());
		assertThat(view().course().status()).isEqualTo("ENDED");

		// 종료 뒤에는 입장이 거부되고, 주기 점검 대상(READY 수업)에서도 빠진다.
		var props = new LabProperties("i", "p", "m", 15, "http://lab.example.com:8080", "127.0.0.1", Path.of("."),
				"b:1", "t/course-", "swunion-test-s");
		SessionService join = new SessionService(sessions, courses, docker, props, (port, timeout) -> true, json);
		assertThatThrownBy(() -> join.join("DASH01", "새학생")).isInstanceOf(JoinException.class);
		verify(docker, never()).createAndStart(any());
		assertThat(sessions.findByCourse_StatusAndContainerIdIsNotNull(CourseStatus.READY)).isEmpty();
	}

	@Test
	void endContinuesPastStopFailuresAndOnlyMarksStoppedContainers() {
		StudentSession gone = withContainer("가영", "c1"); // 이미 멈췄거나 없는 컨테이너: stop이 던진다
		StudentSession stuck = withContainer("나리", "c2"); // 정말 못 멈춘 컨테이너
		StudentSession fine = withContainer("다은", "c3");
		doThrow(new DockerOperationException("already stopped")).when(docker).stop("c1");
		doThrow(new DockerOperationException("daemon error")).when(docker).stop("c2");
		when(docker.isRunning("c1")).thenReturn(false);
		when(docker.isRunning("c2")).thenReturn(true);

		CourseService.EndResult result = courseService().end(course.getId());

		assertThat(result).isEqualTo(new CourseService.EndResult(2, 1));
		verify(docker).stop("c1");
		verify(docker).stop("c2");
		verify(docker).stop("c3");
		assertThat(sessions.findById(gone.getId()).orElseThrow().isContainerRunning()).isFalse();
		assertThat(sessions.findById(fine.getId()).orElseThrow().isContainerRunning()).isFalse();
		assertThat(sessions.findById(stuck.getId()).orElseThrow().isContainerRunning())
				.as("멈추지 못했으면 실행 중으로 남겨 강사가 알 수 있게 한다").isTrue();
		assertThat(courses.findById(course.getId()).orElseThrow().getStatus()).isEqualTo(CourseStatus.ENDED);
	}

	@Test
	void endOnlyAllowedForReadyCourse() {
		Course building = newCourse("BUILD1", CourseStatus.BUILDING);
		withContainer(building, "cb");
		CourseService courseService = courseService();

		assertThatThrownBy(() -> courseService.end(building.getId())).isInstanceOf(CourseStateException.class);
		assertThat(courses.findById(building.getId()).orElseThrow().getStatus()).isEqualTo(CourseStatus.BUILDING);

		withContainer("가영", "c1");
		courseService.end(course.getId());
		assertThatThrownBy(() -> courseService.end(course.getId())).isInstanceOf(CourseStateException.class);
		assertThat(courseService.end(987654L)).isNull();
		verify(docker).stop("c1");
		verify(docker, never()).stop("cb");
	}

	private StudentSession withContainer(Course c, String containerId) {
		StudentSession s = student(c, "학생-" + containerId);
		s.setContainerId(containerId);
		return sessions.save(s);
	}
}
