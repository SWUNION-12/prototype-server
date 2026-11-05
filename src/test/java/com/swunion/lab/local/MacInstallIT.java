package com.swunion.lab.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseService;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.session.SessionService;
import com.swunion.lab.session.StudentSession;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 이 Mac에서 실제로 조회 명령과 설치 스크립트를 돌린다. 사용자 홈의 uv 상태를 건드리지 않도록
 * 설치 폴더, uv 캐시, uv 파이썬 설치 폴더를 모두 임시 폴더로 돌린다.
 */
@Tag("integration")
@EnabledOnOs(OS.MAC)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
		"spring.config.import=optional:file:./build/no-such.env[.properties]",
		"spring.datasource.url=jdbc:h2:mem:macinstallit",
		"lab.course-image-prefix=" + MacInstallIT.IMAGE_PREFIX,
		"lab.container-name-prefix=" + MacInstallIT.NAME_PREFIX
})
class MacInstallIT {

	private static final Logger log = LoggerFactory.getLogger(MacInstallIT.class);
	static final String IMAGE_PREFIX = "swunion-it/mac-course-";
	static final String NAME_PREFIX = "swunion-it-m";
	static final Path DATA_DIR;
	static final int PORT;

	static {
		try {
			DATA_DIR = Files.createTempDirectory("swunion-it-mac-data");
			try (ServerSocket s = new ServerSocket(0)) {
				PORT = s.getLocalPort();
			}
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("lab.data-dir", DATA_DIR::toString);
		r.add("server.port", () -> PORT);
		// 스크립트가 이 주소로 서버를 부르므로 렌더링되는 주소도 같은 포트여야 한다.
		r.add("lab.public-base-url", () -> "http://127.0.0.1:" + PORT);
	}

	@Autowired
	CourseService courseService;
	@Autowired
	SessionService sessionService;
	@Autowired
	LocalInstallRepository localInstalls;
	@Autowired
	DockerGateway docker;
	@Autowired
	DockerClient client;
	@Autowired
	JsonMapper json;

	private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	@Test
	void realMacRunsDiagnoseInstallAndVerify() throws Exception {
		for (int i = 1; i <= 3; i++) {
			removeImageQuietly(IMAGE_PREFIX + i + ":1");
		}
		Course created = courseService.create("로컬 설치 통합 테스트", "3.12", "six==1.16.0");
		long courseId = created.getId();
		String tag = IMAGE_PREFIX + courseId + ":1";
		StudentSession student = null;
		Path work = Files.createTempDirectory("swunion-it-mac-work");
		try {
			Course c = null;
			for (int i = 0; i < 300; i++) {
				c = courseService.get(courseId);
				if (c.getStatus() == CourseStatus.READY || c.getStatus() == CourseStatus.FAILED) {
					break;
				}
				Thread.sleep(1000);
			}
			assertThat(c.getStatus()).as("오류: %s", c.getErrorSummary()).isEqualTo(CourseStatus.READY);
			student = sessionService.join(c.getEntryCode(), "맥학생", "1234").session();
			String token = student.getToken();
			String base = "http://127.0.0.1:" + PORT;

			// 1) 조회 명령: 서버가 준 명령을 sh -c로 실행한 출력을 그대로 제출
			Map<?, ?> info = json.readValue(get(base + "/api/s/" + token + "/local/install?os=macos").body(), Map.class);
			String diagnoseOut = run(List.of("sh", "-c", (String) info.get("diagnoseCommand")), Map.of(), work);
			log.info("조회 명령 출력:\n{}", diagnoseOut);
			assertThat(post(base + "/api/s/" + token + "/local/diagnostics",
					json.writeValueAsString(Map.of("os", "macos", "text", diagnoseOut))).statusCode()).isEqualTo(204);
			assertThat(localInstalls.findBySessionId(student.getId()).orElseThrow().getStatus())
					.isEqualTo(LocalStatus.DIAGNOSED);

			// 2) 설치 명령: 임시 폴더로 돌려 사용자 홈의 uv 상태를 건드리지 않는다.
			Path installDir = work.resolve("install");
			Map<String, String> env = Map.of("SWUNION_DIR", installDir.toString(),
					"UV_CACHE_DIR", work.resolve("uv-cache").toString(),
					"UV_PYTHON_INSTALL_DIR", work.resolve("uv-python").toString());
			String installOut = run(List.of("sh", "-c", (String) info.get("installCommand")), env, work);
			log.info("설치 스크립트 출력:\n{}", installOut);
			assertThat(Files.exists(installDir.resolve("uv.lock"))).isTrue();

			// 3) 출력 전체를 verify API에 제출
			HttpResponse<String> v = post(base + "/api/s/" + token + "/local/verify",
					json.writeValueAsString(Map.of("text", installOut)));
			log.info("verify 응답: {}", v.body());
			assertThat(v.statusCode()).isEqualTo(200);
			Map<?, ?> res = json.readValue(v.body(), Map.class);
			assertThat(res.get("ok")).as(installOut).isEqualTo(true);
			assertThat(res.get("passed")).isEqualTo(1);
			assertThat(res.get("total")).isEqualTo(1);
			assertThat(localInstalls.findBySessionId(student.getId()).orElseThrow().getStatus())
					.isEqualTo(LocalStatus.OK);

			// 4) code.zip: 컨테이너에 .venv를 일부러 만들어 두고 제외되는지 본다.
			docker.exec(student.getContainerId(), List.of("sh", "-c",
					"mkdir -p /home/coder/project/.venv /home/coder/project/__pycache__ && "
							+ "echo x > /home/coder/project/.venv/x && echo x > /home/coder/project/__pycache__/a.pyc"),
					Duration.ofSeconds(20));
			HttpResponse<byte[]> zip = http.send(HttpRequest.newBuilder(URI.create(base + "/l/" + token + "/code.zip"))
					.build(), HttpResponse.BodyHandlers.ofByteArray());
			assertThat(zip.statusCode()).isEqualTo(200);
			List<String> names = new ArrayList<>();
			try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip.body()))) {
				for (ZipEntry e; (e = in.getNextEntry()) != null;) {
					names.add(e.getName());
				}
			}
			log.info("zip 항목: {}", names);
			assertThat(names).contains("course-" + courseId + "/hello.py");
			assertThat(names).noneMatch(n -> n.contains(".venv") || n.contains("__pycache__") || n.endsWith(".pyc"));

			// 5) 허용하지 않은 파일은 404
			assertThat(get(base + "/l/" + token + "/files/secret.txt").statusCode()).isEqualTo(404);
		}
		finally {
			if (student != null) {
				try {
					docker.remove(student.getContainerId());
				}
				catch (RuntimeException ignored) {
				}
				try {
					if (student.getVolumeName() != null && student.getVolumeName().startsWith(NAME_PREFIX)) {
						docker.removeVolume(student.getVolumeName());
					}
				}
				catch (RuntimeException ignored) {
				}
			}
			removeImageQuietly(tag);
			deleteTree(work);
		}
	}

	private HttpResponse<String> get(String url) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create(url)).build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpResponse<String> post(String url, String body) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private String run(List<String> cmd, Map<String, String> env, Path cwd) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
		pb.environment().putAll(env);
		Process p = pb.start();
		p.getOutputStream().close();
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(p.waitFor(300, TimeUnit.SECONDS)).isTrue();
		return out;
	}

	private void removeImageQuietly(String tag) {
		try {
			client.removeImageCmd(tag).withForce(true).exec();
		}
		catch (RuntimeException ignored) {
		}
	}

	private static void deleteTree(Path dir) {
		try (var s = Files.walk(dir)) {
			s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
		catch (IOException ignored) {
		}
	}
}
