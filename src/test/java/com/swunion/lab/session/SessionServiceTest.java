package com.swunion.lab.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.swunion.lab.config.LabProperties;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.docker.ContainerSpec;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.docker.DockerOperationException;
import com.swunion.lab.docker.ExecResult;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest
class SessionServiceTest {

	@Autowired
	SessionRepository sessions;
	@Autowired
	CourseRepository courses;

	FakeDocker docker = new FakeDocker();
	boolean healthy = true;
	List<Integer> probedPorts = new ArrayList<>();
	SessionService service;
	Course ready;

	@BeforeEach
	void setUp() {
		var props = new LabProperties("i", "p", "m", 15, "http://lab.example.com:8080", "127.0.0.1", Path.of("."),
				"b:1", "t/course-", "swunion-test-s");
		HealthWaiter waiter = (port, timeout) -> {
			probedPorts.add(port);
			return healthy;
		};
		service = new SessionService(sessions, courses, docker, props, waiter, JsonMapper.builder().build());
		ready = newCourse("ABC123", CourseStatus.READY);
	}

	private Course newCourse(String code, CourseStatus status) {
		Course c = new Course("수업", "3.12", "six==1.16.0");
		c.setStatus(status);
		c.setEntryCode(code);
		c.setImageName("t/course-1:1");
		return courses.save(c);
	}

	@Test
	void joinCreatesContainerWithLimitsAndUniqueSecrets() {
		StudentSession a = service.join("ABC123", " 철수 ");
		StudentSession b = service.join("ABC123", "영희");

		assertThat(a.getDisplayName()).isEqualTo("철수");
		assertThat(a.getToken()).matches("[0-9a-f]{64}").isNotEqualTo(b.getToken());
		assertThat(a.getIdePassword()).hasSize(24).matches("[A-Za-z0-9]+").isNotEqualTo(b.getIdePassword());
		assertThat(a.getEnvStatus()).isEqualTo(EnvStatus.UNKNOWN);
		assertThat(a.getIssuesJson()).isEqualTo("[]");
		assertThat(a.isNeedsHelp()).isFalse();

		ContainerSpec spec = docker.specs.get(0);
		assertThat(spec.name()).isEqualTo("swunion-test-s" + a.getId());
		assertThat(spec.volumeName()).isEqualTo("swunion-test-s" + a.getId());
		assertThat(spec.image()).isEqualTo("t/course-1:1");
		assertThat(spec.env()).containsEntry("PASSWORD", a.getIdePassword());
		assertThat(spec.bindAddress()).isEqualTo("127.0.0.1");
		assertThat(spec.containerPort()).isEqualTo(8080);
		assertThat(spec.volumeMountPath()).isEqualTo("/home/coder/project");
		assertThat(spec.labels()).containsEntry("swunion.session", String.valueOf(a.getId()))
				.containsEntry("swunion.course", String.valueOf(ready.getId()));
		assertThat(a.getHostPort()).isNotEqualTo(b.getHostPort());
		assertThat(probedPorts).containsExactly(a.getHostPort(), b.getHostPort());
		assertThat(a.getStartupMillis()).isNotNull();
		assertThat(a.isContainerRunning()).isTrue();
		assertThat(sessions.findById(a.getId()).orElseThrow().getContainerId()).isEqualTo("c" + a.getId());
	}

	@Test
	void unhealthyStartupStillReturnsSessionWithoutStartupMillis() {
		healthy = false;
		StudentSession s = service.join("ABC123", "철수");
		assertThat(s.getStartupMillis()).isNull();
		assertThat(s.getContainerId()).isNotNull();
	}

	@Test
	void rejectsUnknownOrNotReadyCourse() {
		newCourse("BUILD1", CourseStatus.BUILDING);
		assertThatThrownBy(() -> service.join("ZZZZZZ", "철수")).isInstanceOf(JoinException.class);
		assertThatThrownBy(() -> service.join("BUILD1", "철수")).isInstanceOf(JoinException.class);
		assertThatThrownBy(() -> service.join(null, "철수")).isInstanceOf(JoinException.class);
		assertThat(docker.specs).isEmpty();
	}

	@Test
	void rejectsBlankOrLongName() {
		assertThatThrownBy(() -> service.join("ABC123", "")).isInstanceOf(JoinException.class);
		assertThatThrownBy(() -> service.join("ABC123", "   ")).isInstanceOf(JoinException.class);
		assertThatThrownBy(() -> service.join("ABC123", null)).isInstanceOf(JoinException.class);
		assertThatThrownBy(() -> service.join("ABC123", "가".repeat(31))).isInstanceOf(JoinException.class);
		assertThat(service.join("ABC123", "가".repeat(30)).getId()).isNotNull();
	}

	@Test
	void containerFailureDeletesRowAndRemovesContainer() {
		docker.failCreate = true;
		assertThatThrownBy(() -> service.join("ABC123", "철수")).isInstanceOf(JoinException.class)
				.hasMessageContaining("컨테이너를 만들지 못했습니다");
		assertThat(sessions.count()).isZero();
	}

	@Test
	void byTokenAndNeedsHelp() {
		StudentSession s = service.join("ABC123", "철수");
		assertThat(service.byToken(s.getToken()).getId()).isEqualTo(s.getId());
		assertThatThrownBy(() -> service.byToken("nope")).isInstanceOf(SessionNotFoundException.class);
		service.flagNeedsHelp(s.getId(), "막힘");
		assertThat(service.byToken(s.getToken()).isNeedsHelp()).isTrue();
		assertThat(service.byToken(s.getToken()).getNeedsHelpReason()).isEqualTo("막힘");
		service.clearNeedsHelp(s.getId());
		assertThat(service.byToken(s.getToken()).isNeedsHelp()).isFalse();
		assertThat(service.byToken(s.getToken()).getNeedsHelpReason()).isNull();
	}

	@Test
	void ideUrlUsesPublicHost() {
		StudentSession s = service.join("ABC123", "철수");
		assertThat(service.ideUrl(s)).isEqualTo("http://lab.example.com:" + s.getHostPort() + "/");
	}

	@Test
	void restartPersistsNewHostPort() {
		StudentSession s = service.join("ABC123", "철수");
		int old = s.getHostPort();
		docker.ports.put(s.getContainerId(), 55555);
		probedPorts.clear();

		assertThat(service.restartContainer(s.getId())).isTrue();

		assertThat(docker.restarted).containsExactly(s.getContainerId());
		assertThat(sessions.findById(s.getId()).orElseThrow().getHostPort()).isEqualTo(55555).isNotEqualTo(old);
		assertThat(probedPorts).containsExactly(55555);

		healthy = false;
		assertThat(service.restartContainer(s.getId())).isFalse();
	}

	static class FakeDocker implements DockerGateway {
		final List<ContainerSpec> specs = new ArrayList<>();
		final Map<String, Integer> ports = new HashMap<>();
		final List<String> restarted = new ArrayList<>();
		final List<String> removed = new ArrayList<>();
		boolean failCreate;
		int nextPort = 40000;

		public String createAndStart(ContainerSpec spec) {
			if (failCreate) {
				throw new DockerOperationException("boom");
			}
			specs.add(spec);
			String id = "c" + spec.labels().get("swunion.session");
			ports.put(id, nextPort++);
			return id;
		}

		public int hostPort(String id, int containerPort) { return ports.get(id); }
		public void restart(String id) { restarted.add(id); }
		public boolean isRunning(String id) { return true; }
		public void remove(String id) { removed.add(id); }
		public boolean imageExists(String image) { return true; }
		public String buildImage(Path d, String t, Consumer<String> l) { throw new UnsupportedOperationException(); }
		public ExecResult runOnce(String i, List<String> c, Duration t) { throw new UnsupportedOperationException(); }
		public ExecResult exec(String id, List<String> c, Duration t) { throw new UnsupportedOperationException(); }
		public ExecResult exec(String id, List<String> c, String w, Duration t) { throw new UnsupportedOperationException(); }
		public void stop(String id) { }
		public void removeVolume(String v) { }
		public InputStream copyArchive(String id, String p) { throw new UnsupportedOperationException(); }
	}
}
