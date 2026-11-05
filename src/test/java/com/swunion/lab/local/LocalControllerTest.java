package com.swunion.lab.local;

import static com.swunion.lab.session.StudentRequests.cookie;
import static com.swunion.lab.session.StudentRequests.xsrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.course.CourseService;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.docker.DockerOperationException;
import com.swunion.lab.lifecycle.LifecycleService;
import com.swunion.lab.session.SessionRepository;
import com.swunion.lab.session.StudentSession;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
		"spring.config.import=optional:file:./build/no-such.env[.properties]",
		"spring.datasource.url=jdbc:h2:mem:localctrl",
		"lab.public-base-url=http://192.168.0.5:8080"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class LocalControllerTest {

	static final Path DATA_DIR;
	static final String BASE = "http://192.168.0.5:8080";
	static final String EXPIRED = "설치 링크가 만료되었습니다. 코딩책상 화면에서 설치 명령을 다시 받아 주세요.";
	static final String NOT_FOUND = "입장 정보를 찾을 수 없습니다. 다시 입장해 주세요.";

	static {
		try {
			DATA_DIR = Files.createTempDirectory("swunion-localctrl-data");
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("lab.data-dir", DATA_DIR::toString);
	}

	/** 다운로드 코드의 10분을 실제로 기다리지 않도록 시각을 테스트가 정한다. */
	@TestConfiguration
	static class Clocks {

		@Bean
		@Primary
		MutableClock testClock() {
			return new MutableClock(Instant.parse("2026-11-05T09:00:00Z"));
		}
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	CourseRepository courses;
	@Autowired
	SessionRepository sessions;
	@Autowired
	LocalInstallRepository localInstalls;
	@Autowired
	CourseService courseService;
	@Autowired
	LifecycleService lifecycle;
	@Autowired
	MutableClock clock;
	@Autowired
	JsonMapper json;
	@MockitoBean
	DockerGateway docker;

	Course course;
	StudentSession session;
	String token;

	@BeforeEach
	void setUp() throws Exception {
		clock.set(Instant.parse("2026-11-05T09:00:00Z"));
		Course c = new Course("로컬 테스트", "3.12", "scikit-learn==1.5.2\nsix");
		c.setStatus(CourseStatus.READY);
		c.setBaselineJson("{\"scikit-learn\":\"1.5.2\",\"six\":\"1.16.0\",\"numpy\":\"2.0.0\"}");
		course = courses.save(c);
		// 토큰은 unique라 테스트마다 새 값이 필요하다.
		token = String.format("%064x", System.nanoTime());
		StudentSession s = new StudentSession(course, "철수", token, "pw");
		s.setContainerId("cid-1");
		session = sessions.save(s);
		Path spec = courseService.specDir(course.getId());
		Files.createDirectories(spec);
		Files.writeString(spec.resolve("pyproject.toml"), "[project]\nname='x'\n");
		Files.writeString(spec.resolve("uv.lock"), "version = 1\n");
		Files.writeString(spec.resolve(".python-version"), "3.12\n");
		Files.writeString(spec.resolve("secret.txt"), "nope");
	}

	// ---- 링크 받기 ----

	@Test
	void linkGivesTenMinuteCodeForBothOs() throws Exception {
		Map<String, String> mac = link("macos");
		String code = codeOf(mac);
		assertThat(code).matches("[0-9a-f]{64}").isNotEqualTo(token);
		assertThat(mac).containsEntry("installCommand", "curl -fsSL \"" + BASE + "/l/" + code + "/install.sh\" | sh")
				.containsEntry("scriptUrl", BASE + "/l/" + code + "/install.sh")
				.containsEntry("zipUrl", BASE + "/l/" + code + "/code.zip")
				.containsEntry("expiresAt", "2026-11-05T09:10:00Z");
		assertThat(mac.get("diagnoseCommand")).contains("os=macos");
		assertThat(mac.keySet()).containsExactly("diagnoseCommand", "installCommand", "scriptUrl", "zipUrl",
				"expiresAt");
		assertThat(mac.values()).noneMatch(v -> v.contains(token));

		Map<String, String> win = link("windows");
		assertThat(win.get("installCommand")).contains("install.ps1 | iex").contains(codeOf(win));
		assertThat(win.get("scriptUrl")).endsWith("/install.ps1");
		assertThat(codeOf(win)).as("부를 때마다 새 코드").isNotEqualTo(code);

		linkRequest("linux").andExpect(status().isBadRequest());
	}

	@Test
	void linkNeedsTheStudentCookie() throws Exception {
		mvc.perform(post("/api/me/local/link").param("os", "macos").with(xsrf())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value(NOT_FOUND));
		mvc.perform(post("/api/me/local/link").param("os", "macos").cookie(cookie("deadbeef")).with(xsrf()))
				.andExpect(status().isNotFound());
		// 예전 GET 주소는 없다. 코드를 만드는 요청은 POST(CSRF 검사)로만 받는다.
		mvc.perform(get("/api/me/local/install").param("os", "macos").cookie(cookie(token)))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/me/local/link").param("os", "macos").cookie(cookie(token)))
				.andExpect(r -> assertThat(r.getResponse().getStatus()).isNotEqualTo(200));
	}

	// ---- 코드로 받는 스크립트와 파일 ----

	@Test
	void scriptsAreTextPlainAndCarryTheCodeNotTheToken() throws Exception {
		String code = codeOf(link("macos"));
		String sh = mvc.perform(get("/l/" + code + "/install.sh")).andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(sh).doesNotContain("{{").doesNotContain(token)
				.contains("CODE=\"" + code + "\"", "/l/$CODE/files/$f", "COURSE_ID=\"" + course.getId() + "\"");
		String ps1 = mvc.perform(get("/l/" + code + "/install.ps1")).andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(ps1).doesNotContain("{{").doesNotContain(token)
				.contains("$Code = '" + code + "'", "/l/$Code/files/$f");
		mvc.perform(get("/l/nosuchcode/install.sh")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value(EXPIRED));
	}

	@Test
	void filesAreWhitelisted() throws Exception {
		String code = codeOf(link("macos"));
		mvc.perform(get("/l/" + code + "/files/pyproject.toml")).andExpect(status().isOk())
				.andExpect(content().string("[project]\nname='x'\n"));
		mvc.perform(get("/l/" + code + "/files/uv.lock")).andExpect(status().isOk());
		mvc.perform(get("/l/" + code + "/files/.python-version")).andExpect(status().isOk())
				.andExpect(content().string("3.12\n"));
		// verify.py는 직접 지정한 패키지의 기준 버전을 담는다. numpy는 직접 지정이 아니므로 빠진다.
		String py = mvc.perform(get("/l/" + code + "/files/verify.py")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(py).contains("{\"scikit-learn\": \"1.5.2\", \"six\": \"1.16.0\"}").doesNotContain("numpy");
		mvc.perform(get("/l/" + code + "/files/secret.txt")).andExpect(status().isNotFound());
		mvc.perform(get("/l/" + code + "/files/..%2Fsecret.txt")).andExpect(status().is4xxClientError());
		mvc.perform(get("/l/" + code + "/files/..")).andExpect(status().is4xxClientError());
		mvc.perform(get("/l/" + code + "/files/install.sh")).andExpect(status().isNotFound());
	}

	// ---- 코드가 무효가 되는 경우 ----

	// 설치 스크립트는 파일 여러 개를 받으므로 10분 안에는 몇 번이든 쓸 수 있다. 10분이 되면 모든 주소가 404다.
	@Test
	void codeWorksForTenMinutesThenEveryAddressIs404() throws Exception {
		String code = codeOf(link("macos"));
		stubZip();
		clock.set(Instant.parse("2026-11-05T09:09:59Z"));
		for (String path : codePaths(code)) {
			mvc.perform(get(path)).andExpect(status().isOk());
		}

		clock.set(Instant.parse("2026-11-05T09:10:00Z"));
		for (String path : codePaths(code)) {
			mvc.perform(get(path)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value(EXPIRED));
		}
		// 시각을 되돌려도 한 번 만료로 버린 코드는 살아나지 않는다.
		clock.set(Instant.parse("2026-11-05T09:05:00Z"));
		mvc.perform(get("/l/" + code + "/install.sh")).andExpect(status().isNotFound());
		// 학생은 화면에서 다시 받는다.
		String again = codeOf(link("macos"));
		mvc.perform(get("/l/" + again + "/install.sh")).andExpect(status().isOk());
	}

	// 재입장은 토큰을 바꾼다. 예전 브라우저에서 받아 둔 설치 명령도 바로 막힌다.
	@Test
	void rejoinInvalidatesOutstandingCodes() throws Exception {
		String code = codeOf(link("macos"));
		stubZip();
		mvc.perform(get("/l/" + code + "/install.sh")).andExpect(status().isOk());

		String rotated = String.format("%064x", System.nanoTime() + 1);
		sessions.rotateToken(session.getId(), rotated);

		for (String path : codePaths(code)) {
			mvc.perform(get(path)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value(EXPIRED));
		}
		// 새 쿠키로 받은 새 코드는 된다.
		token = rotated;
		mvc.perform(get("/l/" + codeOf(link("macos")) + "/install.sh")).andExpect(status().isOk());
	}

	@Test
	void deletingTheStudentInvalidatesCodes() throws Exception {
		String code = codeOf(link("windows"));
		mvc.perform(get("/l/" + code + "/install.ps1")).andExpect(status().isOk());

		lifecycle.deleteStudent(course.getId(), session.getId());

		mvc.perform(get("/l/" + code + "/install.ps1")).andExpect(status().isNotFound());
		mvc.perform(get("/l/" + code + "/files/uv.lock")).andExpect(status().isNotFound());
		mvc.perform(get("/api/me").cookie(cookie(token))).andExpect(status().isNotFound());
	}

	@Test
	void deletingTheCourseInvalidatesCodes() throws Exception {
		String code = codeOf(link("macos"));
		mvc.perform(get("/l/" + code + "/files/pyproject.toml")).andExpect(status().isOk());

		course.setStatus(CourseStatus.ENDED);
		courses.save(course);
		lifecycle.deleteCourse(course.getId(), course.getName());

		mvc.perform(get("/l/" + code + "/files/pyproject.toml")).andExpect(status().isNotFound());
		mvc.perform(get("/l/" + code + "/code.zip")).andExpect(status().isNotFound());
	}

	// 토큰과 다운로드 코드는 로그 어디에도 남지 않는다(주소가 로그로 새는 길을 막는다).
	@Test
	void tokensAndCodesNeverReachTheLog(CapturedOutput output) throws Exception {
		String code = codeOf(link("macos"));
		mvc.perform(get("/l/" + code + "/install.sh")).andExpect(status().isOk());
		when(docker.copyArchive(any(), any())).thenThrow(new DockerOperationException("no such container"));
		mvc.perform(get("/l/" + code + "/code.zip")).andExpect(status().isConflict());
		clock.set(Instant.parse("2026-11-05T09:30:00Z"));
		mvc.perform(get("/l/" + code + "/install.sh")).andExpect(status().isNotFound());

		assertThat(output.getAll()).doesNotContain(token).doesNotContain(code);
	}

	// ---- 쿠키로 하는 진단·검증 ----

	@Test
	void diagnosticsThenVerifyOk() throws Exception {
		mvc.perform(post("/api/me/local/diagnostics").cookie(cookie(token)).with(xsrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"os\":\"macos\",\"text\":\"os=macos\\narch=arm64\\npython=3.12.4\\n\"}"))
				.andExpect(status().isNoContent());
		LocalInstall li = localInstalls.findBySessionId(session.getId()).orElseThrow();
		assertThat(li.getStatus()).isEqualTo(LocalStatus.DIAGNOSED);
		assertThat(li.getArch()).isEqualTo("arm64");

		String text = "noise\\n---SWUNION-VERIFY-BEGIN---\\nverify=ok\\npython=3.12.7\\npackages=2/2\\n---SWUNION-VERIFY-END---\\n";
		verify(text).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
				.andExpect(jsonPath("$.python").value("3.12.7")).andExpect(jsonPath("$.passed").value(2))
				.andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.failed").isEmpty());
		li = localInstalls.findBySessionId(session.getId()).orElseThrow();
		assertThat(li.getStatus()).isEqualTo(LocalStatus.OK);
		assertThat(localInstalls.findBySessionCourseId(course.getId())).hasSize(1);
		assertThat(sessions.findById(session.getId()).orElseThrow().isNeedsHelp()).isFalse();
	}

	@Test
	void diagnosticsAndVerifyNeedTheCookie() throws Exception {
		mvc.perform(post("/api/me/local/diagnostics").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"os\":\"macos\",\"text\":\"os=macos\\narch=arm64\\npython=3.12.4\\n\"}"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value(NOT_FOUND));
		mvc.perform(post("/api/me/local/verify").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\":\"x\"}")).andExpect(status().isNotFound());
		assertThat(localInstalls.findBySessionId(session.getId())).isEmpty();
	}

	@Test
	void verifyFailWithManyPackagesKeepsReasonWithin50() throws Exception {
		String failed = IntStream.range(0, 12).mapToObj(i -> "package" + i + ":ImportError").collect(Collectors.joining(","));
		String text = "---SWUNION-VERIFY-BEGIN---\\nverify=fail\\npython=3.12.7\\npackages=0/12\\nfailed=" + failed
				+ "\\n---SWUNION-VERIFY-END---";
		verify(text).andExpect(status().isOk());
		String reason = sessions.findById(session.getId()).orElseThrow().getNeedsHelpReason();
		assertThat(reason).hasSize(50).startsWith("로컬 설치 검증 실패: package0").endsWith("…");
	}

	@Test
	void verifyFailFlagsInstructorAndKeepsOneRow() throws Exception {
		String text = "---SWUNION-VERIFY-BEGIN---\\nverify=fail\\npython=3.12.7\\npackages=1/2\\nfailed=six:ImportError\\n---SWUNION-VERIFY-END---";
		verify(text).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
				.andExpect(jsonPath("$.failed[0]").value("six:ImportError"))
				.andExpect(jsonPath("$.message").value(containsString("클라우드")));
		StudentSession s = sessions.findById(session.getId()).orElseThrow();
		assertThat(s.isNeedsHelp()).isTrue();
		assertThat(s.getNeedsHelpReason()).isEqualTo("로컬 설치 검증 실패: six:ImportError");
		assertThat(localInstalls.findBySessionId(session.getId()).orElseThrow().getStatus())
				.isEqualTo(LocalStatus.FAIL);
		// 다시 제출해도 행은 하나.
		verify(text).andExpect(status().isOk());
		assertThat(localInstalls.findBySessionCourseId(course.getId())).hasSize(1);
	}

	@Test
	void badPastesAre400() throws Exception {
		mvc.perform(post("/api/me/local/diagnostics").cookie(cookie(token)).with(xsrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"os\":\"windows\",\"text\":\"os=macos\\narch=arm64\\npython=none\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isString());
		verify("nothing here").andExpect(status().isBadRequest());
		assertThat(localInstalls.findBySessionId(session.getId())).isEmpty();
	}

	// ---- 코드 zip ----

	@Test
	void codeZipByCookieAndByCodeStripsProjectAndExcludesVenv() throws Exception {
		stubZip();
		assertZip(mvc.perform(get("/api/me/code.zip").cookie(cookie(token))));
		stubZip();
		assertZip(mvc.perform(get("/l/" + codeOf(link("macos")) + "/code.zip")));
		mvc.perform(get("/api/me/code.zip")).andExpect(status().isNotFound());
	}

	@Test
	void codeZipIs409WhenDockerFails() throws Exception {
		when(docker.copyArchive(any(), any())).thenThrow(new DockerOperationException("no such container"));
		mvc.perform(get("/api/me/code.zip").cookie(cookie(token))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value(containsString("클라우드")));
		mvc.perform(get("/l/" + codeOf(link("macos")) + "/code.zip")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value(containsString("클라우드")));
	}

	// ---- 도우미 ----

	private ResultActions linkRequest(String os) throws Exception {
		return mvc.perform(post("/api/me/local/link").param("os", os).cookie(cookie(token)).with(xsrf()));
	}

	private Map<String, String> link(String os) throws Exception {
		String body = linkRequest(os).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
		return json.readValue(body, new TypeReference<Map<String, String>>() {
		});
	}

	private static String codeOf(Map<String, String> link) {
		String url = link.get("zipUrl");
		return url.substring((BASE + "/l/").length(), url.length() - "/code.zip".length());
	}

	private static List<String> codePaths(String code) {
		return List.of("/l/" + code + "/install.sh", "/l/" + code + "/install.ps1",
				"/l/" + code + "/files/pyproject.toml", "/l/" + code + "/files/verify.py", "/l/" + code + "/code.zip");
	}

	private ResultActions verify(String text) throws Exception {
		return mvc.perform(post("/api/me/local/verify").cookie(cookie(token)).with(xsrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"" + text + "\"}"));
	}

	private void stubZip() throws Exception {
		ByteArrayOutputStream tar = new ByteArrayOutputStream();
		try (TarArchiveOutputStream t = new TarArchiveOutputStream(tar)) {
			for (String p : List.of("project/hello.py", "project/.venv/lib.py")) {
				byte[] d = "x".getBytes(StandardCharsets.UTF_8);
				TarArchiveEntry e = new TarArchiveEntry(p);
				e.setSize(d.length);
				t.putArchiveEntry(e);
				t.write(d);
				t.closeArchiveEntry();
			}
		}
		when(docker.copyArchive(eq("cid-1"), eq("/home/coder/project")))
				.thenAnswer(inv -> new ByteArrayInputStream(tar.toByteArray()));
	}

	private void assertZip(ResultActions result) throws Exception {
		byte[] zip = result.andExpect(status().isOk())
				.andExpect(header().string("Content-Disposition", containsString("course-" + course.getId() + ".zip")))
				.andExpect(header().string("Content-Type", "application/zip"))
				.andReturn().getResponse().getContentAsByteArray();
		List<String> names = new ArrayList<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			for (ZipEntry e; (e = in.getNextEntry()) != null;) {
				names.add(e.getName());
			}
		}
		assertThat(names).containsExactly("course-" + course.getId() + "/hello.py");
	}

	static class MutableClock extends Clock {

		private volatile Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void set(Instant now) {
			this.now = now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
