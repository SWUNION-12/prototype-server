package com.swunion.lab.session;

import static org.hamcrest.Matchers.containsString;
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
import com.swunion.lab.docker.DockerGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
				.content("{\"entryCode\":\"CTRL01\",\"name\":\"철수\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.token").isString())
				.andReturn().getResponse().getContentAsString();
		String token = body.replaceAll(".*\"token\":\"([0-9a-f]{64})\".*", "$1");

		mvc.perform(get("/api/s/" + token)).andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("철수"))
				.andExpect(jsonPath("$.courseName").value("파이썬 기초"))
				.andExpect(jsonPath("$.ideUrl").value("http://localhost:40123/"))
				.andExpect(jsonPath("$.idePassword").isString())
				.andExpect(jsonPath("$.envStatus").value("UNKNOWN"))
				.andExpect(jsonPath("$.issues").isEmpty())
				.andExpect(jsonPath("$.needsHelp").value(false))
				.andExpect(jsonPath("$.latestHelp").doesNotExist())
				.andExpect(jsonPath("$.containerId").doesNotExist());
	}

	@Test
	void badCodeIs400AndUnknownTokenIs404() throws Exception {
		mvc.perform(post("/api/join").contentType(MediaType.APPLICATION_JSON)
				.content("{\"entryCode\":\"NOPE00\",\"name\":\"철수\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(containsString("입장 코드")));
		mvc.perform(get("/api/s/deadbeef")).andExpect(status().isNotFound());
	}
}
