package com.swunion.lab.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.swunion.lab.config.DockerConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class DockerJavaGatewayIT {

	static final String BASE = "swunion/lab-base:1";
	static DockerClient client;
	static DockerGateway gw;

	final List<String> containers = new ArrayList<>();
	final List<String> volumes = new ArrayList<>();

	@BeforeAll
	static void setUp() {
		client = new DockerConfig().dockerClient();
		gw = new DockerJavaGateway(client);
	}

	@AfterEach
	void cleanUp() {
		for (String c : containers) {
			try {
				gw.remove(c);
			}
			catch (RuntimeException ignored) {
			}
		}
		for (String v : volumes) {
			try {
				gw.removeVolume(v);
			}
			catch (RuntimeException ignored) {
			}
		}
	}

	private String start() {
		String vol = "swunion-it-vol-" + UUID.randomUUID();
		volumes.add(vol);
		String id = gw.createAndStart(new ContainerSpec("swunion-it-" + UUID.randomUUID(), BASE,
				Map.of("PASSWORD", "x"), vol, "/home/coder/project", "127.0.0.1", 8080, Map.of("swunion.test", "1")));
		containers.add(id);
		return id;
	}

	private static List<String> entryNames(TarArchiveInputStream tar) throws Exception {
		List<String> names = new ArrayList<>();
		TarArchiveEntry e;
		while ((e = tar.getNextEntry()) != null) {
			names.add(e.getName());
		}
		return names;
	}

	@Test
	void execRunsAsCoder() throws Exception {
		String id = start();
		assertThat(gw.exec(id, List.of("id", "-un"), Duration.ofSeconds(10)).stdout().trim()).isEqualTo("coder");
		assertThat(gw.hostPort(id, 8080)).isPositive();
		assertThat(gw.isRunning(id)).isTrue();
		try (var tar = new TarArchiveInputStream(gw.copyArchive(id, "/home/coder/project"))) {
			assertThat(entryNames(tar)).anyMatch(n -> n.endsWith("hello.py"));
		}
		gw.remove(id);
		assertThat(gw.isRunning(id)).isFalse();
	}

	@Test
	void execWorkDirExitCodeStderrAndTimeout() {
		String id = start();
		assertThat(gw.exec(id, List.of("pwd"), "/opt/lab", Duration.ofSeconds(10)).stdout().trim())
				.isEqualTo("/opt/lab");
		ExecResult r = gw.exec(id, List.of("sh", "-c", "echo out; echo err >&2; exit 3"), Duration.ofSeconds(10));
		assertThat(r.exitCode()).isEqualTo(3);
		assertThat(r.stdout().trim()).isEqualTo("out");
		assertThat(r.stderr().trim()).isEqualTo("err");
		assertThatThrownBy(() -> gw.exec(id, List.of("sleep", "30"), Duration.ofSeconds(1)))
				.isInstanceOf(DockerOperationException.class).hasMessageContaining("timed out");
	}

	@Test
	void restartAndStop() {
		String id = start();
		gw.restart(id);
		assertThat(gw.isRunning(id)).isTrue();
		gw.stop(id);
		assertThat(gw.isRunning(id)).isFalse();
	}

	@Test
	void isRunningFalseForMissing() {
		assertThat(gw.isRunning("swunion-it-does-not-exist")).isFalse();
	}

	@Test
	void runOnceReturnsOutput() {
		assertThat(gw.runOnce(BASE, List.of("uv", "--version"), Duration.ofSeconds(30)).stdout()).contains("0.11.29");
	}

	@Test
	void buildImageBuildsTrivialDockerfile(@TempDir Path ctx) throws Exception {
		Files.writeString(ctx.resolve("Dockerfile"), "FROM " + BASE + "\nRUN true\n");
		String tag = "swunion-it-build:" + UUID.randomUUID().toString().substring(0, 8);
		List<String> log = new ArrayList<>();
		try {
			String imageId = gw.buildImage(ctx, tag, log::add);
			assertThat(imageId).isNotBlank();
			assertThat(gw.imageExists(tag)).isTrue();
			assertThat(log).isNotEmpty();
		}
		finally {
			try {
				client.removeImageCmd(tag).withForce(true).exec();
			}
			catch (RuntimeException ignored) {
			}
		}
		assertThat(gw.imageExists(tag)).isFalse();
	}

	private static Set<String> allContainerIds() {
		return client.listContainersCmd().withShowAll(true).exec().stream().map(Container::getId)
				.collect(Collectors.toSet());
	}

	@Test
	void buildImageFailureCarriesLogTailAndLeavesNoContainer(@TempDir Path ctx) throws Exception {
		Files.writeString(ctx.resolve("Dockerfile"), "FROM " + BASE + "\nRUN echo boom-marker && false\n");
		String tag = "swunion-it-build:" + UUID.randomUUID().toString().substring(0, 8);
		Set<String> before = allContainerIds();
		Set<String> leaked = new HashSet<>();
		try {
			assertThatThrownBy(() -> gw.buildImage(ctx, tag, l -> {
			})).isInstanceOf(DockerOperationException.class).hasMessageContaining("boom-marker");
		}
		finally {
			leaked.addAll(allContainerIds());
			leaked.removeAll(before);
			// 실패한 RUN 단계의 중간 컨테이너가 남았다면 이 테스트가 만든 것이므로 지운다.
			for (String id : leaked) {
				gw.remove(id);
			}
			try {
				client.removeImageCmd(tag).withForce(true).exec();
			}
			catch (RuntimeException ignored) {
			}
		}
		assertThat(leaked).as("실패한 빌드의 중간 컨테이너").isEmpty();
	}
}
