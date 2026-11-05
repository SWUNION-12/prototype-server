package com.swunion.lab.course;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
		"spring.datasource.url=jdbc:h2:mem:ctrltest"
})
@AutoConfigureMockMvc
class CourseControllerTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	CourseRepository courses;
	@Autowired
	SessionRepository sessions;
	// 컨트롤러 테스트에서는 백그라운드 발급이 실제로 돌지 않게 막는다.
	@MockitoBean
	CourseProvisioner provisioner;

	@Test
	void createsAndReadsCourse() throws Exception {
		mvc.perform(post("/api/courses").with(user("instructor")).with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"파이썬 기초\",\"pythonVersion\":\"3.12\",\"packages\":\"six==1.16.0\\nrich\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("LOCKING"))
				.andExpect(jsonPath("$.packages[0]").value("six==1.16.0")).andExpect(jsonPath("$.packages[1]").value("rich"))
				.andExpect(jsonPath("$.studentCount").value(0))
				.andExpect(jsonPath("$.imageName").doesNotExist());
		mvc.perform(get("/api/courses").with(user("instructor"))).andExpect(status().isOk());
	}

	@Test
	void listIncludesStudentCount() throws Exception {
		Course two = courses.save(new Course("학생 둘", "3.12", "six==1.16.0"));
		sessions.save(new StudentSession(two, "철수", "tok-count-1", "pw"));
		sessions.save(new StudentSession(two, "영희", "tok-count-2", "pw"));
		Course none = courses.save(new Course("학생 없음", "3.12", "six==1.16.0"));

		mvc.perform(get("/api/courses").with(user("instructor")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == " + two.getId() + ")].studentCount").value(2))
				.andExpect(jsonPath("$[?(@.id == " + none.getId() + ")].studentCount").value(0));
		mvc.perform(get("/api/courses/" + two.getId()).with(user("instructor")))
				.andExpect(jsonPath("$.studentCount").value(2));
		mvc.perform(get("/api/courses/" + none.getId()).with(user("instructor")))
				.andExpect(jsonPath("$.studentCount").value(0));
	}

	@Test
	void invalidSpecIs400() throws Exception {
		mvc.perform(post("/api/courses").with(user("instructor")).with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"x\",\"pythonVersion\":\"2.7\",\"packages\":\"six\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(containsString("파이썬")));
	}

	@Test
	void unknownIdIs404() throws Exception {
		mvc.perform(get("/api/courses/99999").with(user("instructor")))
				.andExpect(status().isNotFound());
	}

	@Test
	void requiresAuth() throws Exception {
		mvc.perform(get("/api/courses")).andExpect(status().isUnauthorized());
	}
}
