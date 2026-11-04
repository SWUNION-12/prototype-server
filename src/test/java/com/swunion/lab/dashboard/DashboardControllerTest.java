package com.swunion.lab.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.swunion.lab.agent.AgentActionFixtures;
import com.swunion.lab.agent.AgentActionRepository;
import com.swunion.lab.agent.Classification;
import com.swunion.lab.agent.Outcome;
import com.swunion.lab.agent.Trigger;
import com.swunion.lab.check.Issue;
import com.swunion.lab.check.IssueType;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseProvisioner;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.help.HelpRequest;
import com.swunion.lab.help.HelpRequestRepository;
import com.swunion.lab.local.LocalInstall;
import com.swunion.lab.local.LocalInstallRepository;
import com.swunion.lab.local.LocalStatus;
import com.swunion.lab.session.EnvStatus;
import com.swunion.lab.session.HealthWaiter;
import com.swunion.lab.session.SessionRepository;
import com.swunion.lab.session.StudentSession;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
		"spring.config.import=optional:file:./build/no-such.env[.properties]",
		"spring.datasource.url=jdbc:h2:mem:dashctrl"
})
@AutoConfigureMockMvc
class DashboardControllerTest {

	static final String SECRET_TOKEN = "secret-token-0123456789abcdef";
	static final String SECRET_PASSWORD = "secret-ide-password";
	static final String CONTAINER_ID = "container-id-feedface";

	@Autowired
	MockMvc mvc;
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
	@Autowired
	JsonMapper json;
	@MockitoBean
	DockerGateway docker;
	@MockitoBean
	HealthWaiter health;
	@MockitoBean
	CourseProvisioner provisioner;

	private Course course(String code, CourseStatus status) {
		Course c = new Course("파이썬 기초", "3.12", "six==1.16.0\nrich");
		c.setStatus(status);
		c.setEntryCode(code);
		c.setImageName("t/course-1:1");
		return courses.save(c);
	}

	private StudentSession student(Course c, String name, String tokenSuffix) {
		StudentSession s = new StudentSession(c, name, SECRET_TOKEN + tokenSuffix, SECRET_PASSWORD);
		s.setContainerId(CONTAINER_ID + tokenSuffix);
		s.setVolumeName("vol-" + tokenSuffix);
		s.setHostPort(43210);
		s = sessions.save(s);
		sessions.updateContainerState(s.getId(), 43210, true);
		return s;
	}

	@Test
	void requiresInstructorAuth() throws Exception {
		mvc.perform(get("/api/courses/1/dashboard")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/courses/1/sessions/1/resolve")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/courses/1/end")).andExpect(status().isUnauthorized());
	}

	@Test
	void unknownCourseIs404() throws Exception {
		mvc.perform(get("/api/courses/987654/dashboard").with(httpBasic("instructor", "instructor")))
				.andExpect(status().isNotFound());
		mvc.perform(post("/api/courses/987654/sessions/1/resolve").with(httpBasic("instructor", "instructor")))
				.andExpect(status().isNotFound());
		mvc.perform(post("/api/courses/987654/end").with(httpBasic("instructor", "instructor")))
				.andExpect(status().isNotFound());
	}

	@Test
	void dashboardJsonHasStudentStateAndNoSecrets() throws Exception {
		Course c = course("CTRL01", CourseStatus.READY);
		StudentSession a = student(c, "가영", "a");
		student(c, "나리", "b");
		sessions.updateCheckStateSeen(a.getId(), true, true, EnvStatus.MISMATCH, json.writeValueAsString(List.of(
				new Issue(IssueType.MISSING, "six", "1.16.0", null))), "[\"numpy\"]",
				Instant.parse("2026-11-04T03:00:00Z"));
		sessions.updateEscalation(a.getId(), "재점검 실패: MISSING:six", "MISSING:six");
		helps.save(new HelpRequest(a, "ModuleNotFoundError", "ModuleNotFoundError: x"));
		actions.save(AgentActionFixtures.action(sessions.findById(a.getId()).orElseThrow(), Trigger.CHECK,
				Classification.ENV, Outcome.ESCALATED, "[\"check_environment {} => ok\",\"restore_packages {} => 실패\"]",
				"복구에 실패했습니다.", Instant.parse("2026-11-04T03:00:01Z"), Instant.parse("2026-11-04T03:00:09Z")));
		LocalInstall li = new LocalInstall(sessions.findById(a.getId()).orElseThrow());
		li.setStatus(LocalStatus.FAIL);
		li.setOs("mac");
		li.setArch("arm64");
		li.setFailedJson("[\"numpy\"]");
		locals.save(li);

		String body = mvc.perform(get("/api/courses/" + c.getId() + "/dashboard").with(httpBasic("instructor", "instructor")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.course.id").value(c.getId()))
				.andExpect(jsonPath("$.course.status").value("READY"))
				.andExpect(jsonPath("$.course.entryCode").value("CTRL01"))
				.andExpect(jsonPath("$.course.packages[0]").value("six==1.16.0"))
				.andExpect(jsonPath("$.students.length()").value(2))
				.andExpect(jsonPath("$.students[0].name").value("가영"))
				.andExpect(jsonPath("$.students[0].sessionId").value(a.getId()))
				.andExpect(jsonPath("$.students[0].containerRunning").value(true))
				.andExpect(jsonPath("$.students[0].ideConnected").value(true))
				.andExpect(jsonPath("$.students[0].lastSeenAt").value("2026-11-04T03:00:00Z"))
				.andExpect(jsonPath("$.students[0].envStatus").value("MISMATCH"))
				.andExpect(jsonPath("$.students[0].issues[0]").value("six 없음 (기준 1.16.0)"))
				.andExpect(jsonPath("$.students[0].extras[0]").value("numpy"))
				.andExpect(jsonPath("$.students[0].needsHelp").value(true))
				.andExpect(jsonPath("$.students[0].needsHelpReason").value("재점검 실패: MISSING:six"))
				.andExpect(jsonPath("$.students[0].local.status").value("FAIL"))
				.andExpect(jsonPath("$.students[0].local.failed[0]").value("numpy"))
				.andExpect(jsonPath("$.students[0].recentActions[0].tools[1]").value("restore_packages"))
				.andExpect(jsonPath("$.students[0].recentActions[0].at").value("2026-11-04T03:00:09Z"))
				.andExpect(jsonPath("$.students[1].name").value("나리"))
				.andExpect(jsonPath("$.students[1].local.status").value("NONE"))
				.andExpect(jsonPath("$.repeatedErrors[0].errorType").value("ModuleNotFoundError"))
				.andExpect(jsonPath("$.repeatedErrors[0].count").value(1))
				.andExpect(jsonPath("$.repeatedErrors[0].students[0]").value("가영"))
				.andExpect(jsonPath("$.repeatedErrorsNote").value("도움 버튼을 누른 학생 기준이며 전체 오류 수가 아닙니다."))
				// 학생 행에는 세션 토큰, IDE 비밀번호, 컨테이너 id, 포트, 볼륨 이름이 없다.
				.andExpect(jsonPath("$.students[0].token").doesNotExist())
				.andExpect(jsonPath("$.students[0].idePassword").doesNotExist())
				.andExpect(jsonPath("$.students[0].containerId").doesNotExist())
				.andExpect(jsonPath("$.students[0].hostPort").doesNotExist())
				.andExpect(jsonPath("$.students[0].volumeName").doesNotExist())
				.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("\"token\"", "\"idePassword\"", "\"containerId\"", "\"hostPort\"",
				"\"volumeName\"", SECRET_TOKEN, SECRET_PASSWORD, CONTAINER_ID, "vol-");
	}

	@Test
	void resolveClearsNeedsHelpAndChecksCourseMembership() throws Exception {
		Course c = course("CTRL02", CourseStatus.READY);
		Course other = course("CTRL03", CourseStatus.READY);
		StudentSession mine = student(c, "가영", "c");
		StudentSession theirs = student(other, "나리", "d");
		sessions.updateEscalation(mine.getId(), "원인 불명", "MISSING:six");
		sessions.updateNeedsHelp(theirs.getId(), true, "막힘");

		mvc.perform(post("/api/courses/" + c.getId() + "/sessions/" + theirs.getId() + "/resolve")
				.with(httpBasic("instructor", "instructor"))).andExpect(status().isNotFound());
		mvc.perform(post("/api/courses/" + c.getId() + "/sessions/" + mine.getId() + "/resolve")
				.with(httpBasic("instructor", "instructor"))).andExpect(status().isNoContent());

		mvc.perform(get("/api/courses/" + c.getId() + "/dashboard").with(httpBasic("instructor", "instructor")))
				.andExpect(jsonPath("$.students[0].needsHelp").value(false))
				.andExpect(jsonPath("$.students[0].needsHelpReason").doesNotExist());
		assertThat(sessions.findById(theirs.getId()).orElseThrow().isNeedsHelp()).isTrue();
		assertThat(sessions.findById(mine.getId()).orElseThrow().getLastEscalatedSignature()).isEqualTo("MISSING:six");
	}

	@Test
	void endStopsContainersAndCanBeRepeated() throws Exception {
		Course c = course("CTRL04", CourseStatus.READY);
		student(c, "가영", "e");
		student(c, "나리", "f");

		mvc.perform(post("/api/courses/" + c.getId() + "/end").with(httpBasic("instructor", "instructor")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ENDED"))
				.andExpect(jsonPath("$.stopped").value(2))
				.andExpect(jsonPath("$.failed").value(0));
		verify(docker).stop(CONTAINER_ID + "e");
		verify(docker).stop(CONTAINER_ID + "f");
		verify(docker, never()).removeVolume(anyString());

		// 이미 종료된 수업도 다시 종료할 수 있다. 남아 있거나 되살아난 컨테이너를 다시 멈춘다.
		mvc.perform(post("/api/courses/" + c.getId() + "/end").with(httpBasic("instructor", "instructor")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ENDED"))
				.andExpect(jsonPath("$.stopped").value(2))
				.andExpect(jsonPath("$.failed").value(0));
		verify(docker, times(2)).stop(CONTAINER_ID + "e");
		verify(docker, times(2)).stop(CONTAINER_ID + "f");
		mvc.perform(get("/api/courses/" + c.getId() + "/dashboard").with(httpBasic("instructor", "instructor")))
				.andExpect(jsonPath("$.course.status").value("ENDED"))
				.andExpect(jsonPath("$.students[0].containerRunning").value(false));
	}

	@Test
	void endRefusesCourseThatIsStillProvisioning() throws Exception {
		Course c = course("CTRL05", CourseStatus.BUILDING);
		student(c, "가영", "g");

		mvc.perform(post("/api/courses/" + c.getId() + "/end").with(httpBasic("instructor", "instructor")))
				.andExpect(status().isConflict());

		assertThat(courses.findById(c.getId()).orElseThrow().getStatus()).isEqualTo(CourseStatus.BUILDING);
		verify(docker, never()).stop(anyString());
	}
}
