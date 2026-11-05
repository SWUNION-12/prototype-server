package com.swunion.lab.check;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseService;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.docker.ExecResult;
import com.swunion.lab.session.SessionService;
import com.swunion.lab.session.StudentSession;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
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
		"spring.datasource.url=jdbc:h2:mem:checkit",
		// 실서버 이름과 겹치지 않는 테스트 전용 접두사 (JoinIT와 같은 이유)
		"lab.course-image-prefix=" + CheckIT.IMAGE_PREFIX,
		"lab.container-name-prefix=" + CheckIT.NAME_PREFIX
})
class CheckIT {

	private static final Logger log = LoggerFactory.getLogger(CheckIT.class);
	static final String IMAGE_PREFIX = "swunion-it/check-course-";
	static final String NAME_PREFIX = "swunion-it-chk";
	static final Path DATA_DIR;

	static {
		try {
			DATA_DIR = Files.createTempDirectory("swunion-it-check-data");
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
	@Autowired
	EnvironmentChecker checker;

	@Test
	void detectsMissingPackageIdeConnectionAndRemovedContainer() throws Exception {
		for (int i = 1; i <= 3; i++) {
			removeImageQuietly(IMAGE_PREFIX + i + ":1");
		}
		Course created = courseService.create("점검 통합 테스트", "3.12", "six==1.16.0");
		long courseId = created.getId();
		String tag = IMAGE_PREFIX + courseId + ":1";
		StudentSession s = null;
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
			s = sessionService.join(c.getEntryCode(), "학생", "1234").session();
			final StudentSession joined = s;
			String id = s.getContainerId();

			log.info("접속 전 /proc/net/tcp:\n{}", docker.exec(id, List.of("cat", "/proc/net/tcp", "/proc/net/tcp6"),
					Duration.ofSeconds(10)).stdout());
			CheckResult ok = checker.checkAndRecord(s);
			assertThat(ok.issues()).isEmpty();
			assertThat(ok.containerRunning()).isTrue();
			// 클라이언트가 붙지 않았으면 IDE 접속은 false
			assertThat(ok.ideConnected()).as("접속 전").isFalse();

			// 호스트 포트포워딩으로 TCP 연결을 열어 두면 컨테이너 안 /proc/net/tcp에 ESTABLISHED로 보여야 한다.
			try (Socket sock = new Socket()) {
				sock.connect(new InetSocketAddress("127.0.0.1", s.getHostPort()), 5000);
				OutputStream out = sock.getOutputStream();
				out.write(("GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: keep-alive\r\n\r\n")
						.getBytes(StandardCharsets.US_ASCII));
				out.flush();
				boolean seen = waitFor(() -> checker.check(joined).ideConnected(), 10);
				ExecResult proc = docker.exec(id, List.of("cat", "/proc/net/tcp", "/proc/net/tcp6"), Duration.ofSeconds(10));
				log.info("연결 유지 중 /proc/net/tcp:\n{}", proc.stdout());
				assertThat(seen).as("연결 유지 중 ideConnected").isTrue();
			}
			boolean cleared = waitFor(() -> !checker.check(joined).ideConnected(), 15);
			assertThat(cleared).as("연결 종료 후 ideConnected").isTrue();

			// 패키지 제거 -> MISSING six
			exec(id, List.of("uv", "pip", "uninstall", "--python", "/opt/venv/bin/python", "six"));
			CheckResult missing = checker.checkAndRecord(s);
			assertThat(missing.issues()).containsExactly(new Issue(IssueType.MISSING, "six", "1.16.0", null));
			assertThat(missing.signature()).isEqualTo("MISSING:six");

			// 복구 -> OK
			ExecResult sync = docker.exec(id, List.of("uv", "sync", "--frozen", "--inexact"), "/opt/lab/spec",
					Duration.ofSeconds(120));
			assertThat(sync.exitCode()).as(sync.stderr()).isZero();
			assertThat(checker.checkAndRecord(s).ok()).isTrue();

			// 컨테이너가 사라져도 예외 없이 IDE_DOWN
			docker.remove(id);
			CheckResult gone = checker.checkAndRecord(s);
			assertThat(gone.issues()).extracting(Issue::type).containsExactly(IssueType.IDE_DOWN);
			assertThat(gone.containerRunning()).isFalse();
		}
		finally {
			if (s != null) {
				try {
					docker.remove(s.getContainerId());
				}
				catch (RuntimeException ignored) {
				}
				try {
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

	private void exec(String id, List<String> cmd) {
		ExecResult r = docker.exec(id, cmd, Duration.ofSeconds(60));
		assertThat(r.exitCode()).as(r.stderr()).isZero();
	}

	private boolean waitFor(BooleanSupplier cond, int seconds) throws InterruptedException {
		for (int i = 0; i < seconds * 2; i++) {
			if (cond.getAsBoolean()) {
				return true;
			}
			Thread.sleep(500);
		}
		return false;
	}

	private void removeImageQuietly(String tag) {
		try {
			client.removeImageCmd(tag).withForce(true).exec();
		}
		catch (RuntimeException ignored) {
		}
	}
}
