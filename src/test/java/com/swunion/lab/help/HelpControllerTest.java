package com.swunion.lab.help;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.swunion.lab.agent.AgentTrigger;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseProvisioner;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.session.HealthWaiter;
import com.swunion.lab.session.SessionRepository;
import com.swunion.lab.session.StudentSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
		"spring.config.import=optional:file:./build/no-such.env[.properties]",
		"spring.datasource.url=jdbc:h2:mem:helpctrl"
})
@AutoConfigureMockMvc
class HelpControllerTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	CourseRepository courses;
	@Autowired
	SessionRepository sessions;
	@Autowired
	HelpRequestRepository helps;
	@MockitoBean
	DockerGateway docker;
	@MockitoBean
	HealthWaiter health;
	@MockitoBean
	CourseProvisioner provisioner;
	@MockitoBean
	AgentTrigger trigger;

	private String newToken(String tok) {
		return newToken(tok, CourseStatus.READY);
	}

	private String newToken(String tok, CourseStatus status) {
		Course c = new Course("c", "3.12", "six==1.16.0");
		c.setStatus(status);
		c = courses.save(c);
		sessions.save(new StudentSession(c, "철수", tok, "pw"));
		return tok;
	}

	@Test
	void helpThenLatestHelpThenEscalate() throws Exception {
		String tok = newToken("t-help");
		when(trigger.isRunning(anyLong())).thenReturn(false);
		when(trigger.onHelp(any(), anyLong(), any(), any())).thenReturn(true);

		mvc.perform(post("/api/s/" + tok + "/help").contentType(MediaType.APPLICATION_JSON)
				.content("{\"errorText\":\"KeyError: 'a'\"}"))
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.helpId").isNumber());

		mvc.perform(get("/api/s/" + tok)).andExpect(status().isOk())
				.andExpect(jsonPath("$.latestHelp.status").value("PROCESSING"))
				.andExpect(jsonPath("$.latestHelp.errorType").value("KeyError"))
				.andExpect(jsonPath("$.latestHelp.escalated").value(false))
				.andExpect(jsonPath("$.latestHelp.errorText").doesNotExist());

		mvc.perform(post("/api/s/" + tok + "/escalate")).andExpect(status().isNoContent());
		mvc.perform(get("/api/s/" + tok)).andExpect(jsonPath("$.needsHelp").value(true));
	}

	@Test
	void emptyBodyIsAllowed() throws Exception {
		String tok = newToken("t-empty");
		when(trigger.onHelp(any(), anyLong(), any(), any())).thenReturn(true);
		mvc.perform(post("/api/s/" + tok + "/help")).andExpect(status().isAccepted());
	}

	@Test
	void busyIs409AndUnknownTokenIs404() throws Exception {
		String tok = newToken("t-busy");
		when(trigger.isRunning(anyLong())).thenReturn(true);
		mvc.perform(post("/api/s/" + tok + "/help").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("에이전트가 처리 중입니다. 잠시 후 다시 시도해 주세요"));
		mvc.perform(post("/api/s/nope/help").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNotFound());
		mvc.perform(post("/api/s/nope/escalate")).andExpect(status().isNotFound());
	}

	@Test
	void endedCourseRejectsHelpAndEscalate() throws Exception {
		String tok = newToken("t-ended", CourseStatus.ENDED);
		when(trigger.isRunning(anyLong())).thenReturn(false);
		when(trigger.onHelp(any(), anyLong(), any(), any())).thenReturn(true);

		mvc.perform(post("/api/s/" + tok + "/help").contentType(MediaType.APPLICATION_JSON)
				.content("{\"errorText\":\"KeyError: 'a'\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("종료된 수업입니다"));
		mvc.perform(post("/api/s/" + tok + "/escalate"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("종료된 수업입니다"));

		// 종료 뒤의 도움 요청이 에이전트를 깨워 restart_ide로 컨테이너를 되살리면 안 된다.
		verify(trigger, never()).onHelp(any(), anyLong(), any(), any());
		mvc.perform(get("/api/s/" + tok)).andExpect(status().isOk())
				.andExpect(jsonPath("$.needsHelp").value(false))
				.andExpect(jsonPath("$.latestHelp").doesNotExist());
	}
}
