package com.swunion.lab.course;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.swunion.lab.docker.ContainerSpec;
import com.swunion.lab.docker.DockerGateway;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
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
		"spring.datasource.url=jdbc:h2:mem:provit"
})
class CourseProvisioningIT {

	private static final Logger log = LoggerFactory.getLogger(CourseProvisioningIT.class);
	static final Path DATA_DIR;

	static {
		try {
			DATA_DIR = Files.createTempDirectory("swunion-it-data");
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
	CourseService service;
	@Autowired
	DockerGateway docker;
	@Autowired
	DockerClient client;

	@Test
	void provisionsRealCourse() throws Exception {
		// 인메모리 DB라 id가 1부터 다시 시작해 이전 실행의 이미지 태그와 겹칠 수 있다.
		// 남은 이미지 때문에 빌드 없이 통과하지 않도록, 다음 id의 태그를 미리 지운다.
		// id는 create 전에 알 수 없으므로 create 직후 빌드가 끝나기 전에 지울 수 없다. 그래서 id 1~3 태그를 먼저 정리한다.
		for (int i = 1; i <= 3; i++) {
			try {
				client.removeImageCmd("swunion/course-" + i + ":1").withForce(true).exec();
			}
			catch (RuntimeException ignored) {
			}
		}
		Course created = service.create("통합 테스트 수업", "3.12", "six==1.16.0");
		long id = created.getId();
		String tag = "swunion/course-" + id + ":1";
		long t0 = System.nanoTime();

		Course c = null;
		for (int i = 0; i < 300; i++) {
			c = service.get(id);
			if (c.getStatus() == CourseStatus.READY || c.getStatus() == CourseStatus.FAILED) {
				break;
			}
			Thread.sleep(1000);
		}
		log.info("수업 발급 소요: {}초", Duration.ofNanos(System.nanoTime() - t0).toSeconds());
		assertThat(c.getStatus()).as("상태, 오류: %s", c.getErrorSummary()).isEqualTo(CourseStatus.READY);
		assertThat(c.getEntryCode()).matches("[A-Z0-9]{6}");
		assertThat(service.baseline(c)).containsEntry("six", "1.16.0");
		assertThat(service.specDir(id).resolve("uv.lock")).exists();

		assertThat(client.inspectImageCmd(tag).exec().getId()).isNotBlank();

		String vol = "swunion-it-vol-" + UUID.randomUUID();
		String name = "swunion-it-course-" + UUID.randomUUID();
		String container = null;
		try {
			container = docker.createAndStart(new ContainerSpec(name, tag, Map.of("PASSWORD", "x"), vol,
					"/home/coder/project", "127.0.0.1", 8080, Map.of("swunion.it", "true")));
			var un = docker.exec(container,
					java.util.List.of("uv", "pip", "uninstall", "--python", "/opt/venv/bin/python", "six"),
					Duration.ofSeconds(60));
			assertThat(un.exitCode()).as("uninstall: %s", un.stderr()).isZero();
			assertThat(docker.exec(container, java.util.List.of("uv", "pip", "list", "--python",
					"/opt/venv/bin/python", "--format", "json"), Duration.ofSeconds(60)).stdout())
					.doesNotContain("\"six\"");
			var sync = docker.exec(container, java.util.List.of("uv", "sync", "--frozen", "--inexact"),
					"/opt/lab/spec", Duration.ofSeconds(120));
			assertThat(sync.exitCode()).as("sync: %s", sync.stderr()).isZero();
			assertThat(docker.exec(container, java.util.List.of("uv", "pip", "list", "--python",
					"/opt/venv/bin/python", "--format", "json"), Duration.ofSeconds(60)).stdout())
					.contains("\"six\"");
		}
		finally {
			if (container != null) {
				try {
					docker.remove(container);
				}
				catch (RuntimeException ignored) {
				}
			}
			try {
				docker.removeVolume(vol);
			}
			catch (RuntimeException ignored) {
			}
		}
	}
}
