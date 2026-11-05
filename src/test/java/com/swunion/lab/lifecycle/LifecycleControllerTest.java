package com.swunion.lab.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.swunion.lab.config.LabProperties;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseProvisioner;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.session.HealthWaiter;
import com.swunion.lab.session.SessionRepository;
import com.swunion.lab.session.StudentSession;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.FileSystemUtils;

@SpringBootTest(properties = {
		"spring.config.import=optional:file:./build/no-such.env[.properties]",
		"spring.datasource.url=jdbc:h2:mem:lifecyclectrl"
})
@AutoConfigureMockMvc
class LifecycleControllerTest {

	static final String SECRET_TOKEN = "secret-token-lifecycle-0123456789";
	static final String SECRET_PASSWORD = "secret-ide-password-lifecycle";
	// 수업 삭제가 작업 폴더를 지운다. 기본 data-dir(./data)에는 실제 수업 폴더가 있으므로 반드시 임시 폴더를 쓴다.
	static final Path DATA_DIR = tempDir();

	@DynamicPropertySource
	static void dataDir(DynamicPropertyRegistry registry) {
		registry.add("lab.data-dir", DATA_DIR::toString);
	}

	@AfterAll
	static void removeDataDir() throws IOException {
		FileSystemUtils.deleteRecursively(DATA_DIR);
	}

	private static Path tempDir() {
		try {
			return Files.createTempDirectory("lifecycle-ctrl-");
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	CourseRepository courses;
	@Autowired
	SessionRepository sessions;
	@Autowired
	LabProperties props;
	@MockitoBean
	DockerGateway docker;
	@MockitoBean
	HealthWaiter health;
	@MockitoBean
	CourseProvisioner provisioner;

	// 테스트들이 한 컨텍스트(같은 인메모리 DB)를 나눠 쓰므로 토큰·자원 이름이 테스트 사이에서도 겹치지 않게 한다.
	static final AtomicInteger SEQ = new AtomicInteger();

	@BeforeEach
	void dataDirIsTheTestFolder() {
		assertThat(props.dataDir()).isEqualTo(DATA_DIR);
	}

	private Course course(String name, CourseStatus status) {
		Course c = new Course(name, "3.12", "six==1.16.0");
		c.setStatus(status);
		c.setImageName("t/course-ctrl-" + name + ":1");
		return courses.save(c);
	}

	private StudentSession student(Course c, String name) {
		int seq = SEQ.incrementAndGet();
		StudentSession s = new StudentSession(c, name, name, SECRET_TOKEN + seq, SECRET_PASSWORD, "$2a$10$pinhash");
		s.setContainerId("ctrl-container-" + seq);
		s.setVolumeName("ctrl-volume-" + seq);
		s.setHostPort(43000 + seq);
		return sessions.save(s);
	}

	private MockHttpServletRequestBuilder asInstructor(MockHttpServletRequestBuilder b) {
		return b.with(httpBasic("instructor", "instructor"));
	}

	private static MockHttpServletRequestBuilder deleteCourse(long id, String body) {
		return delete("/api/courses/" + id).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	@Test
	void allLifecycleEndpointsNeedInstructor() throws Exception {
		mvc.perform(post("/api/courses/1/resume")).andExpect(status().isUnauthorized());
		mvc.perform(deleteCourse(1, "{\"confirmName\":\"x\"}")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/courses/1/sessions/1/restart")).andExpect(status().isUnauthorized());
		mvc.perform(delete("/api/courses/1/sessions/1")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/courses/1/sessions/1/reset-pin")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/courses/1/resume").with(httpBasic("instructor", "wrong")))
				.andExpect(status().isUnauthorized());
		verify(docker, never()).remove(anyString());
	}

	@Test
	void resumeEndpoint() throws Exception {
		Course ended = course("재개", CourseStatus.ENDED);
		StudentSession s = student(ended, "가영");
		when(docker.isRunning(s.getContainerId())).thenReturn(true);
		when(health.awaitHealthy(anyInt(), any(Duration.class))).thenReturn(true);

		mvc.perform(asInstructor(post("/api/courses/" + ended.getId() + "/resume")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.started").value(1))
				.andExpect(jsonPath("$.unhealthy").value(0))
				.andExpect(jsonPath("$.recreatedEmpty").value(0))
				.andExpect(jsonPath("$.failed").value(0));
		assertThat(courses.findStatusById(ended.getId())).contains(CourseStatus.READY);

		mvc.perform(asInstructor(post("/api/courses/" + ended.getId() + "/resume")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value(containsString("종료된 수업만")));
		mvc.perform(asInstructor(post("/api/courses/987654/resume")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value(containsString("수업")));
	}

	@Test
	void deleteCourseEndpoint() throws Exception {
		Course c = course("삭제", CourseStatus.ENDED);
		StudentSession s = student(c, "가영");
		Course ready = course("진행", CourseStatus.READY);
		StudentSession keep = student(ready, "나리");
		Path dir = Files.createDirectories(DATA_DIR.resolve("courses").resolve(String.valueOf(c.getId())));
		Files.writeString(dir.resolve("Dockerfile"), "FROM b:1");

		mvc.perform(asInstructor(deleteCourse(c.getId(), "{\"confirmName\":\"삭제 \"}")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value(containsString("수업 이름")));
		mvc.perform(asInstructor(delete("/api/courses/" + c.getId())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").exists());
		mvc.perform(asInstructor(deleteCourse(c.getId(), "{not json")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").exists());
		mvc.perform(asInstructor(deleteCourse(ready.getId(), "{\"confirmName\":\"진행\"}")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").exists());
		mvc.perform(asInstructor(deleteCourse(987654, "{\"confirmName\":\"삭제\"}")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").exists());
		verify(docker, never()).remove(anyString());
		verify(docker, never()).removeVolume(anyString());
		verify(docker, never()).removeImage(anyString());
		assertThat(dir.resolve("Dockerfile")).exists();

		String body = mvc.perform(asInstructor(deleteCourse(c.getId(), "{\"confirmName\":\"삭제\"}")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.removedContainers").value(1))
				.andExpect(jsonPath("$.removedVolumes").value(1))
				.andExpect(jsonPath("$.failures.length()").value(0))
				.andReturn().getResponse().getContentAsString();

		verify(docker).remove(s.getContainerId());
		verify(docker).removeVolume(s.getVolumeName());
		verify(docker).removeImage(c.getImageName());
		verify(docker, never()).remove(keep.getContainerId());
		verify(docker, never()).removeVolume(keep.getVolumeName());
		verify(docker, never()).removeImage(ready.getImageName());
		assertThat(courses.findById(c.getId())).isEmpty();
		assertThat(sessions.findById(s.getId())).isEmpty();
		assertThat(sessions.findById(keep.getId())).isPresent();
		assertThat(dir).doesNotExist();
		assertThat(body).doesNotContain(SECRET_TOKEN, SECRET_PASSWORD);
	}

	@Test
	void studentEndpoints() throws Exception {
		Course c = course("학생관리", CourseStatus.READY);
		Course other = course("다른반", CourseStatus.READY);
		Course ended = course("끝난반", CourseStatus.ENDED);
		StudentSession a = student(c, "가영");
		StudentSession b = student(c, "나리");
		StudentSession theirs = student(other, "다은");
		StudentSession inEnded = student(ended, "라온");
		when(docker.containerExists(a.getContainerId())).thenReturn(true);
		when(docker.hostPort(eq(a.getContainerId()), anyInt())).thenReturn(47500);
		when(health.awaitHealthy(eq(47500), any(Duration.class))).thenReturn(true);
		String base = "/api/courses/" + c.getId() + "/sessions/";

		mvc.perform(asInstructor(post(base + a.getId() + "/restart")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ok").value(true));
		verify(docker).restart(a.getContainerId());
		mvc.perform(asInstructor(post(base + theirs.getId() + "/restart")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").exists());
		mvc.perform(asInstructor(post("/api/courses/" + ended.getId() + "/sessions/" + inEnded.getId() + "/restart")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").exists());

		mvc.perform(asInstructor(post(base + b.getId() + "/reset-pin"))).andExpect(status().isNoContent());
		assertThat(sessions.findById(b.getId()).orElseThrow().getPinHash()).isNull();
		mvc.perform(asInstructor(post(base + theirs.getId() + "/reset-pin")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").exists());
		assertThat(sessions.findById(theirs.getId()).orElseThrow().getPinHash()).isNotNull();

		mvc.perform(asInstructor(delete(base + theirs.getId())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").exists());
		String body = mvc.perform(asInstructor(delete(base + b.getId())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.removedContainers").value(1))
				.andExpect(jsonPath("$.removedVolumes").value(1))
				.andExpect(jsonPath("$.failures.length()").value(0))
				.andReturn().getResponse().getContentAsString();
		verify(docker).remove(b.getContainerId());
		verify(docker).removeVolume(b.getVolumeName());
		verify(docker, never()).remove(a.getContainerId());
		verify(docker, never()).removeImage(anyString());
		assertThat(sessions.findById(b.getId())).isEmpty();
		assertThat(sessions.findById(a.getId())).isPresent();
		assertThat(sessions.findById(theirs.getId())).isPresent();
		assertThat(body).doesNotContain(SECRET_TOKEN, SECRET_PASSWORD);
	}
}
