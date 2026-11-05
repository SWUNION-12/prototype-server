package com.swunion.lab.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseProvisioner;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.agent.Classification;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.help.HelpRequest;
import com.swunion.lab.help.HelpRequestRepository;
import com.swunion.lab.help.HelpStatus;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
		"spring.config.import=optional:file:./build/no-such.env[.properties]",
		"spring.datasource.url=jdbc:h2:mem:studentctrl"
})
@AutoConfigureMockMvc
class StudentControllerTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	CourseRepository courses;
	@Autowired
	SessionRepository sessions;
	@Autowired
	HelpRequestRepository helps;
	@Autowired
	JsonMapper json;
	@MockitoBean
	DockerGateway docker;
	@MockitoBean
	HealthWaiter health;
	@MockitoBean
	CourseProvisioner provisioner;

	@Test
	void joinThenReadWithoutAuth() throws Exception {
		Course c = new Course("파이썬 기초", "3.12", "six==1.16.0");
		c.setStatus(CourseStatus.READY);
		c.setEntryCode("CTRL01");
		c.setImageName("x/course-1:1");
		courses.save(c);
		when(docker.createAndStart(any())).thenReturn("cid-secret");
		when(docker.hostPort("cid-secret", 8080)).thenReturn(40123);
		when(health.awaitHealthy(anyInt(), any())).thenReturn(true);

		String body = mvc.perform(post("/api/join").contentType(MediaType.APPLICATION_JSON)
				.content("{\"entryCode\":\"CTRL01\",\"name\":\"철수\",\"pin\":\"1234\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.token").isString())
				.andExpect(jsonPath("$.resumed").value(false))
				.andReturn().getResponse().getContentAsString();
		String token = tokenOf(body);

		mvc.perform(get("/api/s/" + token)).andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("철수"))
				.andExpect(jsonPath("$.courseName").value("파이썬 기초"))
				.andExpect(jsonPath("$.ideUrl").value("http://localhost:40123/"))
				.andExpect(jsonPath("$.idePassword").isString())
				.andExpect(jsonPath("$.envStatus").value("UNKNOWN"))
				.andExpect(jsonPath("$.issues").isEmpty())
				.andExpect(jsonPath("$.needsHelp").value(false))
				.andExpect(jsonPath("$.workspaceLost").value(false))
				.andExpect(jsonPath("$.latestHelp").doesNotExist())
				.andExpect(jsonPath("$.containerId").doesNotExist());
	}

	@Test
	void studentStatusKeepsItsFieldsAndNeverShowsTheIdeKey() throws Exception {
		Course c = new Course("파이썬 기초", "3.12", "six==1.16.0");
		c.setStatus(CourseStatus.READY);
		c.setEntryCode("CTRL05");
		c.setImageName("x/course-5:1");
		courses.save(c);
		String key = "ab".repeat(32);
		StudentSession s = sessions.save(new StudentSession(c, "민수", "민수", "tok-student-fields", "pw-fields-24-chars-AB",
				null, key));
		s.setHostPort(40125);
		sessions.save(s);

		String body = mvc.perform(get("/api/s/tok-student-fields")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		// 기존 아홉 필드는 그대로이고, 공통 상태 뷰의 issuesText와 courseStatus가 더해진다. 컨테이너 키는 학생 API에도 없다.
		Map<String, Object> fields = json.readValue(body, new TypeReference<Map<String, Object>>() {
		});
		assertThat(fields.keySet()).containsExactlyInAnyOrder("name", "courseName", "ideUrl", "idePassword",
				"envStatus", "issues", "issuesText", "needsHelp", "workspaceLost", "latestHelp", "courseStatus");
		assertThat(body).doesNotContain(key).doesNotContain("ideKey");
		mvc.perform(get("/api/s/tok-student-fields")).andExpect(jsonPath("$.ideUrl").value("http://localhost:40125/"))
				.andExpect(jsonPath("$.idePassword").value("pw-fields-24-chars-AB"))
				.andExpect(jsonPath("$.issuesText").isEmpty());
	}

	@Test
	void statusIncludesCourseStatus() throws Exception {
		Course c = readyCourse("CTRL06", "x/course-6:1");
		sessions.save(new StudentSession(c, "하나", "tok-course-status", "pw"));

		mvc.perform(get("/api/s/tok-course-status")).andExpect(status().isOk())
				.andExpect(jsonPath("$.courseStatus").value("READY"));

		c.setStatus(CourseStatus.ENDED);
		courses.save(c);

		mvc.perform(get("/api/s/tok-course-status")).andExpect(status().isOk())
				.andExpect(jsonPath("$.courseStatus").value("ENDED"));
	}

	@Test
	void latestHelpCarriesClassification() throws Exception {
		Course c = readyCourse("CTRL07", "x/course-7:1");
		StudentSession s = sessions.save(new StudentSession(c, "두리", "tok-help-class", "pw"));
		HelpRequest h = helps.save(new HelpRequest(s, "KeyError", "KeyError: a"));

		// 처리 중에는 분류가 아직 없다. 키는 있고 값만 null이다.
		mvc.perform(get("/api/s/tok-help-class")).andExpect(status().isOk())
				.andExpect(jsonPath("$.latestHelp.status").value("PROCESSING"))
				.andExpect(jsonPath("$.latestHelp.classification").value(nullValue()));

		helps.updateResult(h.getId(), HelpStatus.EXPLAINED, "설명", false, Classification.CODE);

		mvc.perform(get("/api/s/tok-help-class")).andExpect(status().isOk())
				.andExpect(jsonPath("$.latestHelp.classification").value("CODE"));
	}

	@Test
	void statusNeverCarriesPin() throws Exception {
		Course c = readyCourse("CTRL08", "x/course-8:1");
		StudentSession s = new StudentSession(c, "세나", "tok-no-pin", "pw");
		sessions.save(s);
		sessions.setPin(s.getId(), "$2a$10$hashhashhashhashhashhashhashhashhashhashhashhashhashhash");

		String body = mvc.perform(get("/api/s/tok-no-pin")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		Map<String, Object> fields = json.readValue(body, new TypeReference<Map<String, Object>>() {
		});
		assertThat(fields).doesNotContainKeys("pin", "pinHash");
		assertThat(body).doesNotContain("hashhash");
	}

	@Test
	void workspaceLostIsShownOnceTheWorkspaceWasRecreatedEmpty() throws Exception {
		Course c = new Course("파이썬 기초", "3.12", "six==1.16.0");
		c.setStatus(CourseStatus.READY);
		c.setEntryCode("CTRL02");
		c.setImageName("x/course-2:1");
		courses.save(c);
		StudentSession s = sessions.save(new StudentSession(c, "영희", "tok-ws-lost-0001", "pw"));
		s.setHostPort(40124);
		sessions.save(s);

		mvc.perform(get("/api/s/tok-ws-lost-0001")).andExpect(status().isOk())
				.andExpect(jsonPath("$.workspaceLost").value(false));

		sessions.markWorkspaceLost(s.getId(), Instant.parse("2026-11-05T09:30:00Z"));

		mvc.perform(get("/api/s/tok-ws-lost-0001")).andExpect(status().isOk())
				.andExpect(jsonPath("$.workspaceLost").value(true));
	}

	@Test
	void badCodeIs400AndUnknownTokenIs404() throws Exception {
		mvc.perform(post("/api/join").contentType(MediaType.APPLICATION_JSON)
				.content("{\"entryCode\":\"NOPE00\",\"name\":\"철수\",\"pin\":\"1234\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(containsString("입장 코드")));
		mvc.perform(get("/api/s/deadbeef")).andExpect(status().isNotFound());
	}

	@Test
	void joinRequestToStringHidesThePin() {
		// DEBUG 요청 로그가 본문 객체를 찍어도 PIN은 남지 않아야 한다.
		String printed = new StudentController.JoinRequest("CTRL09", "철수", "7391").toString();

		assertThat(printed).doesNotContain("7391").contains("CTRL09").contains("철수").contains("pin=");
	}

	private static String tokenOf(String body) {
		return body.replaceAll(".*\"token\":\"([0-9a-f]{64})\".*", "$1");
	}

	private ResultActions join(String code, String name, String pin) throws Exception {
		return mvc.perform(post("/api/join").contentType(MediaType.APPLICATION_JSON)
				.content("{\"entryCode\":\"" + code + "\",\"name\":\"" + name + "\",\"pin\":\"" + pin + "\"}"));
	}

	private Course readyCourse(String code, String image) {
		Course c = new Course("파이썬 기초", "3.12", "six==1.16.0");
		c.setStatus(CourseStatus.READY);
		c.setEntryCode(code);
		c.setImageName(image);
		return courses.save(c);
	}

	@Test
	void rejoinIs200WithANewTokenAndTheOldTokenStopsWorkingEverywhere() throws Exception {
		readyCourse("CTRL03", "x/course-3:1");
		when(docker.createAndStart(any())).thenReturn("cid-rejoin");
		when(docker.hostPort("cid-rejoin", 8080)).thenReturn(40200);
		when(docker.isRunning("cid-rejoin")).thenReturn(true);
		when(health.awaitHealthy(anyInt(), any())).thenReturn(true);

		String oldToken = tokenOf(join("CTRL03", "민지", "4321").andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString());
		String newToken = tokenOf(join("CTRL03", " 민지 ", "4321").andExpect(status().isOk())
				.andExpect(jsonPath("$.resumed").value(true))
				.andReturn().getResponse().getContentAsString());

		assertThat(newToken).matches("[0-9a-f]{64}").isNotEqualTo(oldToken);
		mvc.perform(get("/api/s/" + oldToken)).andExpect(status().isNotFound());
		mvc.perform(get("/l/" + oldToken + "/install.sh")).andExpect(status().isNotFound());
		mvc.perform(get("/api/s/" + newToken)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("민지"));
		mvc.perform(get("/l/" + newToken + "/install.sh")).andExpect(status().isOk());
	}

	@Test
	void wrongPinIs403LockedIs429AndBadFormatIs400() throws Exception {
		readyCourse("CTRL04", "x/course-4:1");
		when(docker.createAndStart(any())).thenReturn("cid-pin");
		when(docker.hostPort("cid-pin", 8080)).thenReturn(40300);
		when(health.awaitHealthy(anyInt(), any())).thenReturn(true);
		join("CTRL04", "수빈", "1111").andExpect(status().isCreated());

		join("CTRL04", "수빈", "12a4").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value(containsString("PIN")));
		mvc.perform(post("/api/join").contentType(MediaType.APPLICATION_JSON)
				.content("{\"entryCode\":\"CTRL04\",\"name\":\"수빈\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(containsString("PIN")));
		join("CTRL04", "수빈", "0000").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value(containsString("남은 기회 4번")))
				.andExpect(jsonPath("$.token").doesNotExist());
		for (int i = 0; i < 4; i++) {
			join("CTRL04", "수빈", "0000").andExpect(status().isForbidden());
		}
		join("CTRL04", "수빈", "1111").andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.error").value(containsString("분 뒤")))
				.andExpect(jsonPath("$.token").doesNotExist());
	}
}
