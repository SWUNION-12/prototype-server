package com.swunion.lab.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseService;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.docker.DockerGateway;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("integration")
@SpringBootTest(properties = {
		"spring.config.import=optional:file:./build/no-such.env[.properties]",
		"spring.datasource.url=jdbc:h2:mem:joinit",
		// 실서버 이미지·학생 볼륨(swunion-s1 등)과 절대 겹치지 않는 테스트 전용 접두사.
		// 인메모리 H2는 id가 1부터 다시 시작하므로 기본 접두사를 쓰면 실제 학생 볼륨에 붙을 수 있다.
		"lab.course-image-prefix=" + JoinIT.IMAGE_PREFIX,
		"lab.container-name-prefix=" + JoinIT.NAME_PREFIX
})
class JoinIT {

	private static final Logger log = LoggerFactory.getLogger(JoinIT.class);
	static final String IMAGE_PREFIX = "swunion-it/join-course-";
	static final String NAME_PREFIX = "swunion-it-s";
	static final Path DATA_DIR;

	static {
		try {
			DATA_DIR = Files.createTempDirectory("swunion-it-join-data");
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
	CourseService courseService;
	@Autowired
	SessionService sessionService;
	@Autowired
	DockerGateway docker;
	@Autowired
	DockerClient client;

	private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();

	@Test
	void twoStudentsGetIsolatedContainers() throws Exception {
		for (int i = 1; i <= 3; i++) {
			removeImageQuietly(IMAGE_PREFIX + i + ":1");
		}
		Course created = courseService.create("입장 통합 테스트", "3.12", "six==1.16.0");
		long courseId = created.getId();
		String tag = IMAGE_PREFIX + courseId + ":1";
		List<StudentSession> joined = new ArrayList<>();
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

			StudentSession a = sessionService.join(c.getEntryCode(), "학생A");
			joined.add(a);
			StudentSession b = sessionService.join(c.getEntryCode(), "학생B");
			joined.add(b);
			log.info("컨테이너 기동 시간: A={}ms, B={}ms", a.getStartupMillis(), b.getStartupMillis());

			assertThat(a.getHostPort()).isNotEqualTo(b.getHostPort());
			assertThat(a.getStartupMillis()).isNotNull().isPositive();
			assertThat(b.getStartupMillis()).isNotNull().isPositive();
			assertThat(a.getIdePassword()).isNotEqualTo(b.getIdePassword());
			assertThat(status("GET", a.getHostPort(), "/healthz", null)).isEqualTo(200);
			assertThat(status("GET", b.getHostPort(), "/healthz", null)).isEqualTo(200);

			// code-server 로그인: 성공하면 302 + Set-Cookie(code-server-session), 실패하면 200 + 쿠키 없음
			var okA = login(a.getHostPort(), a.getIdePassword());
			var badA = login(a.getHostPort(), b.getIdePassword());
			var okB = login(b.getHostPort(), b.getIdePassword());
			var badB = login(b.getHostPort(), a.getIdePassword());
			log.info("로그인 응답: okA={} cookie={}, badA={} cookie={}", okA.statusCode(), cookie(okA),
					badA.statusCode(), cookie(badA));
			assertThat(cookie(okA)).as("A 성공").contains("code-server-session");
			assertThat(cookie(okB)).as("B 성공").contains("code-server-session");
			assertThat(cookie(badA)).as("A에 B 비밀번호").doesNotContain("code-server-session");
			assertThat(cookie(badB)).as("B에 A 비밀번호").doesNotContain("code-server-session");
		}
		finally {
			for (StudentSession s : joined) {
				try {
					docker.remove(s.getContainerId());
				}
				catch (RuntimeException ignored) {
				}
				try {
					// 영속된 이름만 지운다. 테스트 접두사 아래의 이름일 때만 허용한다.
					if (s.getVolumeName() != null && s.getVolumeName().startsWith(NAME_PREFIX)) {
						docker.removeVolume(s.getVolumeName());
					}
				}
				catch (RuntimeException ignored) {
				}
			}
			removeImageQuietly(tag);
		}
	}

	private HttpResponse<String> login(int port, String password) throws Exception {
		return send("POST", port, "/login", "password=" + java.net.URLEncoder.encode(password,
				java.nio.charset.StandardCharsets.UTF_8));
	}

	private int status(String method, int port, String path, String body) throws Exception {
		return send(method, port, path, body).statusCode();
	}

	private HttpResponse<String> send(String method, int port, String path, String body) throws Exception {
		var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
		if (body == null) {
			b.method(method, HttpRequest.BodyPublishers.noBody());
		}
		else {
			b.header("Content-Type", "application/x-www-form-urlencoded")
					.method(method, HttpRequest.BodyPublishers.ofString(body));
		}
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private String cookie(HttpResponse<?> r) {
		return String.join(";", r.headers().allValues("set-cookie"));
	}

	private void removeImageQuietly(String tag) {
		try {
			client.removeImageCmd(tag).withForce(true).exec();
		}
		catch (RuntimeException ignored) {
		}
	}
}
