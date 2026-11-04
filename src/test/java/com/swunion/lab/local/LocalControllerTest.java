package com.swunion.lab.local;

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
import com.swunion.lab.session.SessionRepository;
import com.swunion.lab.session.StudentSession;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
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

@SpringBootTest(properties = {
		"spring.config.import=optional:file:./build/no-such.env[.properties]",
		"spring.datasource.url=jdbc:h2:mem:localctrl",
		"lab.public-base-url=http://192.168.0.5:8080"
})
@AutoConfigureMockMvc
class LocalControllerTest {

	static final Path DATA_DIR;

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
	@MockitoBean
	DockerGateway docker;

	Course course;
	StudentSession session;
	String token;

	@BeforeEach
	void setUp() throws Exception {
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

	@Test
	void installInfoForBothOs() throws Exception {
		mvc.perform(get("/api/s/" + token + "/local/install").param("os", "macos"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.installCommand")
						.value("curl -fsSL \"http://192.168.0.5:8080/l/" + token + "/install.sh\" | sh"))
				.andExpect(jsonPath("$.scriptUrl").value("http://192.168.0.5:8080/l/" + token + "/install.sh"))
				.andExpect(jsonPath("$.zipUrl").value("http://192.168.0.5:8080/l/" + token + "/code.zip"))
				.andExpect(jsonPath("$.diagnoseCommand").value(containsString("os=macos")));
		mvc.perform(get("/api/s/" + token + "/local/install").param("os", "windows"))
				.andExpect(jsonPath("$.installCommand").value(containsString("install.ps1 | iex")))
				.andExpect(jsonPath("$.scriptUrl").value(containsString("/install.ps1")));
		mvc.perform(get("/api/s/" + token + "/local/install").param("os", "linux")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/s/deadbeef/local/install").param("os", "macos")).andExpect(status().isNotFound());
	}

	@Test
	void scriptsAreTextPlainAndRendered() throws Exception {
		String sh = mvc.perform(get("/l/" + token + "/install.sh")).andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(sh).doesNotContain("{{").contains(token, "COURSE_ID=\"" + course.getId() + "\"");
		String ps1 = mvc.perform(get("/l/" + token + "/install.ps1")).andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(ps1).doesNotContain("{{").contains(token);
		mvc.perform(get("/l/nosuchtoken/install.sh")).andExpect(status().isNotFound());
	}

	@Test
	void filesAreWhitelisted() throws Exception {
		mvc.perform(get("/l/" + token + "/files/pyproject.toml")).andExpect(status().isOk())
				.andExpect(content().string("[project]\nname='x'\n"));
		mvc.perform(get("/l/" + token + "/files/uv.lock")).andExpect(status().isOk());
		mvc.perform(get("/l/" + token + "/files/.python-version")).andExpect(status().isOk())
				.andExpect(content().string("3.12\n"));
		// verify.py는 직접 지정한 패키지의 기준 버전을 담는다. numpy는 직접 지정이 아니므로 빠진다.
		String py = mvc.perform(get("/l/" + token + "/files/verify.py")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(py).contains("{\"scikit-learn\": \"1.5.2\", \"six\": \"1.16.0\"}").doesNotContain("numpy");
		mvc.perform(get("/l/" + token + "/files/secret.txt")).andExpect(status().isNotFound());
		mvc.perform(get("/l/" + token + "/files/..%2Fsecret.txt")).andExpect(status().is4xxClientError());
		mvc.perform(get("/l/" + token + "/files/..")).andExpect(status().is4xxClientError());
		mvc.perform(get("/l/" + token + "/files/install.sh")).andExpect(status().isNotFound());
	}

	@Test
	void diagnosticsThenVerifyOk() throws Exception {
		mvc.perform(post("/api/s/" + token + "/local/diagnostics").contentType(MediaType.APPLICATION_JSON)
				.content("{\"os\":\"macos\",\"text\":\"os=macos\\narch=arm64\\npython=3.12.4\\n\"}"))
				.andExpect(status().isNoContent());
		LocalInstall li = localInstalls.findBySessionId(session.getId()).orElseThrow();
		assertThat(li.getStatus()).isEqualTo(LocalStatus.DIAGNOSED);
		assertThat(li.getArch()).isEqualTo("arm64");

		String text = "noise\\n---SWUNION-VERIFY-BEGIN---\\nverify=ok\\npython=3.12.7\\npackages=2/2\\n---SWUNION-VERIFY-END---\\n";
		mvc.perform(post("/api/s/" + token + "/local/verify").contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\":\"" + text + "\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
				.andExpect(jsonPath("$.python").value("3.12.7")).andExpect(jsonPath("$.passed").value(2))
				.andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.failed").isEmpty());
		li = localInstalls.findBySessionId(session.getId()).orElseThrow();
		assertThat(li.getStatus()).isEqualTo(LocalStatus.OK);
		assertThat(localInstalls.findBySessionCourseId(course.getId())).hasSize(1);
		assertThat(sessions.findById(session.getId()).orElseThrow().isNeedsHelp()).isFalse();
	}

	@Test
	void verifyFailFlagsInstructorAndKeepsOneRow() throws Exception {
		String text = "---SWUNION-VERIFY-BEGIN---\\nverify=fail\\npython=3.12.7\\npackages=1/2\\nfailed=six:ImportError\\n---SWUNION-VERIFY-END---";
		mvc.perform(post("/api/s/" + token + "/local/verify").contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\":\"" + text + "\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
				.andExpect(jsonPath("$.failed[0]").value("six:ImportError"))
				.andExpect(jsonPath("$.message").value(containsString("클라우드")));
		StudentSession s = sessions.findById(session.getId()).orElseThrow();
		assertThat(s.isNeedsHelp()).isTrue();
		assertThat(s.getNeedsHelpReason()).isEqualTo("로컬 설치 검증 실패: six:ImportError");
		assertThat(localInstalls.findBySessionId(session.getId()).orElseThrow().getStatus())
				.isEqualTo(LocalStatus.FAIL);
		// 다시 제출해도 행은 하나.
		mvc.perform(post("/api/s/" + token + "/local/verify").contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\":\"" + text + "\"}")).andExpect(status().isOk());
		assertThat(localInstalls.findBySessionCourseId(course.getId())).hasSize(1);
	}

	@Test
	void badPastesAre400() throws Exception {
		mvc.perform(post("/api/s/" + token + "/local/diagnostics").contentType(MediaType.APPLICATION_JSON)
				.content("{\"os\":\"windows\",\"text\":\"os=macos\\narch=arm64\\npython=none\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isString());
		mvc.perform(post("/api/s/" + token + "/local/verify").contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\":\"nothing here\"}")).andExpect(status().isBadRequest());
		assertThat(localInstalls.findBySessionId(session.getId())).isEmpty();
	}

	@Test
	void codeZipStripsProjectAndExcludesVenv() throws Exception {
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
				.thenReturn(new ByteArrayInputStream(tar.toByteArray()));
		byte[] zip = mvc.perform(get("/l/" + token + "/code.zip")).andExpect(status().isOk())
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

	@Test
	void codeZipIs409WhenDockerFails() throws Exception {
		when(docker.copyArchive(any(), any())).thenThrow(new DockerOperationException("no such container"));
		mvc.perform(get("/l/" + token + "/code.zip")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value(containsString("클라우드")));
	}
}
