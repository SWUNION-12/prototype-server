package com.swunion.lab.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.swunion.lab.config.LabProperties;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseProvisioner;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.course.CourseService;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.docker.ExecResult;
import com.swunion.lab.session.SessionRepository;
import com.swunion.lab.session.StudentRequests;
import com.swunion.lab.session.StudentSession;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 Docker에서 수업 재개, 컨테이너가 사라진 뒤의 되찾기, 볼륨까지 사라진 뒤의 되찾기, 수업 삭제를 확인한다.
 * 수업 이미지 빌드가 가장 오래 걸려 수업 하나를 네 테스트가 순서대로 나눠 쓴다. 앞의 세 테스트는 학생을 따로 두고,
 * 마지막 테스트가 수업을 지운다.
 */
@Tag("integration")
@SpringBootTest(properties = {
		"spring.config.import=optional:file:./build/no-such.env[.properties]",
		"spring.datasource.url=jdbc:h2:mem:lifecycleit",
		// 실서버 이미지·학생 볼륨과 겹치지 않는 테스트 전용 접두사(JoinIT와 같은 이유).
		"lab.course-image-prefix=" + LifecycleIT.IMAGE_PREFIX,
		"lab.container-name-prefix=" + LifecycleIT.NAME_PREFIX,
		// 점검이 지워진 컨테이너를 IDE 중단으로 보고 에이전트를 부르면, 테스트가 되찾기로 확인하려는 재생성을 에이전트가
		// 먼저 해 버린다. 이 테스트는 되찾기와 재개 경로만 본다.
		"lab.check-enabled=false"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LifecycleIT {

	private static final Logger log = LoggerFactory.getLogger(LifecycleIT.class);
	static final String IMAGE_PREFIX = "swunion-it/lifecycle-course-";
	static final String NAME_PREFIX = "swunion-it-lc";
	static final String COURSE_NAME = "수명 관리 통합 테스트";
	static final String PROJECT = "/home/coder/project/";
	static final Path DATA_DIR;

	static {
		try {
			DATA_DIR = Files.createTempDirectory("swunion-it-lifecycle-data");
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("lab.data-dir", DATA_DIR::toString);
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	CourseService courseService;
	@Autowired
	CourseRepository courses;
	@Autowired
	SessionRepository sessions;
	@Autowired
	DockerGateway docker;
	@Autowired
	DockerClient client;
	@Autowired
	LabProperties props;
	@Autowired
	JsonMapper json;

	private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER).build();

	private long courseId;
	private String entryCode;
	private String imageTag;
	// 이 테스트가 본 모든 컨테이너 id와 볼륨 이름. 정리와 "삭제 뒤 아무것도 없음" 확인에 함께 쓴다.
	private final Set<String> seenContainers = new LinkedHashSet<>();
	private final Set<String> seenVolumes = new LinkedHashSet<>();

	@BeforeAll
	void readyCourse() throws Exception {
		assertThat(props.dataDir()).isEqualTo(DATA_DIR);
		// 인메모리 H2는 id가 1부터 시작한다. 이전 실행이 남긴 같은 태그가 있으면 빌드 결과를 헷갈리지 않게 먼저 지운다.
		for (int i = 1; i <= 3; i++) {
			removeImageQuietly(IMAGE_PREFIX + i + ":1");
		}
		Course created = courseService.create(COURSE_NAME, "3.12", "six==1.16.0");
		courseId = created.getId();
		imageTag = IMAGE_PREFIX + courseId + ":1";
		Course c = null;
		for (int i = 0; i < 300; i++) {
			c = courseService.get(courseId);
			if (c.getStatus() == CourseStatus.READY || c.getStatus() == CourseStatus.FAILED) {
				break;
			}
			Thread.sleep(1000);
		}
		assertThat(c.getStatus()).as("오류: %s", c.getErrorSummary()).isEqualTo(CourseStatus.READY);
		assertThat(c.getImageName()).isEqualTo(imageTag);
		entryCode = c.getEntryCode();
	}

	@AfterAll
	void cleanUp() {
		try {
			for (StudentSession s : sessions.findAll()) {
				remember(s);
			}
		}
		catch (RuntimeException e) {
			log.warn("정리할 세션 목록을 읽지 못했습니다: {}", e.getMessage());
		}
		for (String id : seenContainers) {
			try {
				client.removeContainerCmd(id).withForce(true).exec();
			}
			catch (NotFoundException alreadyGone) {
				// 수업 삭제 테스트가 이미 지웠다.
			}
			catch (RuntimeException e) {
				log.warn("컨테이너 {} 정리 실패: {}", id, e.getMessage());
			}
		}
		for (String v : seenVolumes) {
			try {
				// 이 테스트 접두사 아래의 이름만 지운다.
				if (v.startsWith(NAME_PREFIX)) {
					docker.removeVolume(v);
				}
			}
			catch (RuntimeException e) {
				log.warn("볼륨 {} 정리 실패: {}", v, e.getMessage());
			}
		}
		if (imageTag != null) {
			removeImageQuietly(imageTag);
		}
		FileSystemUtils.deleteRecursively(DATA_DIR.toFile());
	}

	// ---- 재개 ----

	@Test
	@Order(1)
	void resumeKeepsFiles() throws Exception {
		requireCourseStatus(CourseStatus.READY);
		Joined a = join("학생A", "1234", 201);
		StudentSession before = session(a.token());
		writeFile(before.getContainerId(), "keep.txt", "A의 코드 그대로");

		MockHttpServletResponse ended = mvc.perform(asInstructor(post("/api/courses/" + courseId + "/end")))
				.andReturn().getResponse();
		assertThat(ended.getStatus()).isEqualTo(200);
		assertThat(docker.isRunning(before.getContainerId())).as("종료 뒤 컨테이너").isFalse();
		assertThat(docker.volumeExists(before.getVolumeName())).as("종료 뒤 볼륨").isTrue();
		requireCourseStatus(CourseStatus.ENDED);

		MockHttpServletResponse resumed = mvc.perform(asInstructor(post("/api/courses/" + courseId + "/resume")))
				.andReturn().getResponse();
		assertThat(resumed.getStatus()).as(resumed.getContentAsString()).isEqualTo(200);
		Map<String, Object> r = map(resumed);
		log.info("재개 응답: {}", r);
		assertThat(r).containsEntry("started", 1).containsEntry("unhealthy", 0).containsEntry("recreatedEmpty", 0)
				.containsEntry("failed", 0);
		requireCourseStatus(CourseStatus.READY);

		StudentSession after = session(a.token());
		// 멈춘 컨테이너를 다시 켠 것이다. 볼륨도 IDE 비밀번호도 그대로다.
		assertThat(after.getContainerId()).isEqualTo(before.getContainerId());
		assertThat(after.getVolumeName()).isEqualTo(before.getVolumeName());
		assertThat(after.getIdePassword()).isEqualTo(before.getIdePassword());
		assertThat(after.isContainerRunning()).isTrue();
		assertThat(docker.isRunning(after.getContainerId())).isTrue();
		assertThat(readFile(after.getContainerId(), "keep.txt")).isEqualTo("A의 코드 그대로");
		// 포트는 다시 켜며 바뀔 수 있다. 저장된 포트로 IDE가 응답해야 한다.
		assertThat(status(after.getHostPort(), "/healthz")).isEqualTo(200);
		// 재개한 수업에 다시 입장할 수 있다.
		assertThat(join("학생A", "1234", 200).resumed()).isTrue();
	}

	// ---- 컨테이너가 사라진 뒤 되찾기 ----

	@Test
	@Order(2)
	void rejoinRecreatesWithSameVolume() throws Exception {
		requireCourseStatus(CourseStatus.READY);
		Joined b = join("학생B", "5678", 201);
		StudentSession before = session(b.token());
		writeFile(before.getContainerId(), "keep.txt", "B의 코드 그대로");
		String otherBefore = otherStudentContainer(before.getId());

		client.removeContainerCmd(before.getContainerId()).withForce(true).exec();
		assertThat(docker.containerExists(before.getContainerId())).isFalse();

		// 이름은 앞뒤 공백을 무시한다.
		Joined back = join("  학생B ", "5678", 200);
		assertThat(back.resumed()).isTrue();
		assertThat(back.token()).isNotEqualTo(b.token());
		StudentSession after = session(back.token());
		assertThat(after.getId()).isEqualTo(before.getId());
		assertThat(after.getContainerId()).isNotEqualTo(before.getContainerId());
		assertThat(after.getVolumeName()).isEqualTo(before.getVolumeName());
		assertThat(docker.isRunning(after.getContainerId())).isTrue();
		assertThat(readFile(after.getContainerId(), "keep.txt")).isEqualTo("B의 코드 그대로");
		assertThat(after.getWorkspaceLostAt()).isNull();

		// IDE 비밀번호는 바뀌지 않는다. 새 컨테이너가 같은 비밀번호로 로그인을 받는다.
		assertThat(after.getIdePassword()).isEqualTo(before.getIdePassword());
		assertThat(status(after.getHostPort(), "/healthz")).isEqualTo(200);
		assertThat(cookie(login(after.getHostPort(), after.getIdePassword()))).contains("code-server-session");

		// 예전 토큰은 더 쓸 수 없고, 새 토큰의 학생 화면은 작업 공간 유실을 알리지 않는다.
		assertThat(mvc.perform(get("/api/me").cookie(StudentRequests.cookie(b.token()))).andReturn().getResponse()
				.getStatus()).isEqualTo(404);
		Map<String, Object> me = map(mvc.perform(get("/api/me").cookie(StudentRequests.cookie(back.token())))
				.andReturn().getResponse());
		assertThat(me).containsEntry("workspaceLost", false);

		// 다른 학생의 컨테이너는 건드리지 않았다.
		assertThat(otherStudentContainer(before.getId())).isEqualTo(otherBefore);
	}

	// ---- 볼륨까지 사라진 뒤 되찾기 ----

	@Test
	@Order(3)
	void rejoinAfterVolumeRemovalRecreatesEmptyAndFlagsInstructor() throws Exception {
		requireCourseStatus(CourseStatus.READY);
		Joined c = join("학생C", "4321", 201);
		StudentSession before = session(c.token());
		writeFile(before.getContainerId(), "lost.txt", "사라질 코드");

		// 누군가 docker rm -f와 docker volume rm을 직접 친 상황
		client.removeContainerCmd(before.getContainerId()).withForce(true).exec();
		client.removeVolumeCmd(before.getVolumeName()).exec();
		assertThat(docker.volumeExists(before.getVolumeName())).isFalse();

		Joined back = join("학생C", "4321", 200);
		assertThat(back.resumed()).isTrue();
		StudentSession after = session(back.token());
		assertThat(after.getContainerId()).isNotEqualTo(before.getContainerId());
		// 같은 이름의 빈 볼륨이 새로 붙었다.
		assertThat(after.getVolumeName()).isEqualTo(before.getVolumeName());
		assertThat(docker.volumeExists(after.getVolumeName())).isTrue();
		assertThat(docker.isRunning(after.getContainerId())).isTrue();
		ExecResult test = docker.exec(after.getContainerId(), List.of("test", "-e", PROJECT + "lost.txt"),
				Duration.ofSeconds(10));
		assertThat(test.exitCode()).as("빈 작업 공간").isNotZero();
		assertThat(after.getWorkspaceLostAt()).isNotNull();

		Map<String, Object> me = map(mvc.perform(get("/api/me").cookie(StudentRequests.cookie(back.token())))
				.andReturn().getResponse());
		assertThat(me).containsEntry("workspaceLost", true).containsEntry("needsHelp", true);

		Map<String, Object> row = dashboardRow("학생C");
		log.info("대시보드 학생C: needsHelp={}, reason={}", row.get("needsHelp"), row.get("needsHelpReason"));
		assertThat(row).containsEntry("needsHelp", true);
		assertThat((String) row.get("needsHelpReason")).contains("작업 공간 유실");
		// 다른 학생은 강사 도움 필요로 올라가지 않았다.
		assertThat(dashboardRow("학생B")).containsEntry("needsHelp", false);
	}

	// ---- 수업 삭제 ----

	@Test
	@Order(4)
	void deleteCourseLeavesNothing() throws Exception {
		List<StudentSession> students = sessions.findByCourse_Id(courseId);
		assertThat(students).hasSize(3);
		students.forEach(this::remember);
		Path courseDir = CourseProvisioner.courseDir(props.dataDir(), courseId);
		assertThat(courseDir).exists();

		// READY 수업은 지울 수 없다.
		assertThat(deleteCourse(COURSE_NAME).getStatus()).isEqualTo(409);
		assertThat(mvc.perform(asInstructor(post("/api/courses/" + courseId + "/end"))).andReturn().getResponse()
				.getStatus()).isEqualTo(200);
		// 이름이 정확히 같지 않으면 400이고 아무것도 지우지 않는다.
		assertThat(deleteCourse(COURSE_NAME + " ").getStatus()).isEqualTo(400);
		assertThat(courses.existsById(courseId)).isTrue();
		assertThat(docker.imageExists(imageTag)).isTrue();

		MockHttpServletResponse deleted = deleteCourse(COURSE_NAME);
		assertThat(deleted.getStatus()).as(deleted.getContentAsString()).isEqualTo(200);
		Map<String, Object> r = map(deleted);
		log.info("삭제 응답: {}", r);
		assertThat(r).containsEntry("removedContainers", 3).containsEntry("removedVolumes", 3)
				.containsEntry("failures", List.of());

		for (String id : seenContainers) {
			assertThat(docker.containerExists(id)).as("컨테이너 %s", id).isFalse();
		}
		for (String v : seenVolumes) {
			assertThat(docker.volumeExists(v)).as("볼륨 %s", v).isFalse();
		}
		assertThat(docker.imageExists(imageTag)).as("수업 이미지").isFalse();
		assertThat(courseDir).doesNotExist();
		// 기록하지 못한 채 남은 것도 없다. 이 접두사는 이 테스트만 쓴다.
		assertThat(containersNamed(NAME_PREFIX)).isEmpty();
		assertThat(volumesNamed(NAME_PREFIX)).isEmpty();

		assertThat(courses.existsById(courseId)).isFalse();
		assertThat(sessions.findByCourse_Id(courseId)).isEmpty();
		String list = mvc.perform(asInstructor(get("/api/courses"))).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
		assertThat(list).doesNotContain(COURSE_NAME);
		assertThat(mvc.perform(asInstructor(get("/api/courses/" + courseId + "/dashboard"))).andReturn()
				.getResponse().getStatus()).isEqualTo(404);
	}

	// ---- 도우미 ----

	private record Joined(String token, boolean resumed) {
	}

	private Joined join(String name, String pin, int expectedStatus) throws Exception {
		String body = json.writeValueAsString(Map.of("entryCode", entryCode, "name", name, "pin", pin));
		MvcResult joined = mvc.perform(post("/api/join").contentType(MediaType.APPLICATION_JSON).content(body))
				.andReturn();
		MockHttpServletResponse res = joined.getResponse();
		assertThat(res.getStatus()).as(res.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(expectedStatus);
		Map<String, Object> m = map(res);
		// 토큰은 본문이 아니라 학생 쿠키로 온다.
		String token = StudentRequests.token(joined);
		remember(session(token));
		return new Joined(token, Boolean.TRUE.equals(m.get("resumed")));
	}

	private StudentSession session(String token) {
		return sessions.findByToken(token).orElseThrow();
	}

	private void remember(StudentSession s) {
		if (s.getContainerId() != null) {
			seenContainers.add(s.getContainerId());
		}
		if (s.getVolumeName() != null) {
			seenVolumes.add(s.getVolumeName());
		}
	}

	private void requireCourseStatus(CourseStatus expected) {
		assertThat(courses.findStatusById(courseId)).as("수업 상태").contains(expected);
	}

	private String otherStudentContainer(long exceptSessionId) {
		return sessions.findByCourse_Id(courseId).stream().filter(s -> s.getId() != exceptSessionId)
				.map(s -> s.getContainerId() + "@" + docker.isRunning(s.getContainerId())).sorted()
				.reduce("", (x, y) -> x + "," + y);
	}

	private void writeFile(String containerId, String file, String content) {
		ExecResult r = docker.exec(containerId, List.of("sh", "-c", "printf '%s' \"$1\" > \"$2\"", "sh", content,
				PROJECT + file), Duration.ofSeconds(10));
		assertThat(r.exitCode()).as(r.stderr()).isZero();
	}

	private String readFile(String containerId, String file) {
		ExecResult r = docker.exec(containerId, List.of("cat", PROJECT + file), Duration.ofSeconds(10));
		assertThat(r.exitCode()).as(r.stderr()).isZero();
		return r.stdout();
	}

	private MockHttpServletResponse deleteCourse(String confirmName) throws Exception {
		return mvc.perform(asInstructor(delete("/api/courses/" + courseId)).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("confirmName", confirmName)))).andReturn().getResponse();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> dashboardRow(String name) throws Exception {
		MockHttpServletResponse res = mvc.perform(asInstructor(get("/api/courses/" + courseId + "/dashboard")))
				.andReturn().getResponse();
		assertThat(res.getStatus()).isEqualTo(200);
		List<Map<String, Object>> students = (List<Map<String, Object>>) map(res).get("students");
		return students.stream().filter(s -> name.equals(s.get("name"))).findFirst().orElseThrow();
	}

	private MockHttpServletRequestBuilder asInstructor(MockHttpServletRequestBuilder b) {
		return b.with(user(props.instructorUsername())).with(csrf());
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(MockHttpServletResponse res) throws Exception {
		return json.readValue(res.getContentAsString(StandardCharsets.UTF_8), Map.class);
	}

	private List<String> containersNamed(String prefix) {
		return client.listContainersCmd().withShowAll(true).withNameFilter(List.of(prefix)).exec().stream()
				.map(Container::getNames).filter(Objects::nonNull).flatMap(Arrays::stream)
				.filter(n -> n.startsWith("/" + prefix)).toList();
	}

	private List<String> volumesNamed(String prefix) {
		List<InspectVolumeResponse> vols = client.listVolumesCmd().withFilter("name", List.of(prefix)).exec()
				.getVolumes();
		return vols == null ? List.of()
				: vols.stream().map(InspectVolumeResponse::getName).filter(n -> n.startsWith(prefix)).toList();
	}

	private int status(int port, String path) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
				.timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	private HttpResponse<String> login(int port, String password) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login"))
				.timeout(Duration.ofSeconds(10)).header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString("password=" + URLEncoder.encode(password,
						StandardCharsets.UTF_8))).build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String cookie(HttpResponse<?> r) {
		return String.join(";", r.headers().allValues("set-cookie"));
	}

	private void removeImageQuietly(String tag) {
		try {
			client.removeImageCmd(tag).withForce(true).exec();
		}
		catch (NotFoundException ignored) {
		}
		catch (RuntimeException e) {
			log.warn("이미지 {} 정리 실패: {}", tag, e.getMessage());
		}
	}
}
