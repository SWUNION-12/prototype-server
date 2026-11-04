package com.swunion.lab.check;

import static org.assertj.core.api.Assertions.assertThat;

import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.course.CourseService;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.docker.ContainerSpec;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.docker.DockerOperationException;
import com.swunion.lab.docker.ExecResult;
import com.swunion.lab.session.EnvStatus;
import com.swunion.lab.session.SessionRepository;
import com.swunion.lab.session.StudentSession;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest
class EnvironmentCheckerTest {

	@Autowired
	SessionRepository sessions;
	@Autowired
	CourseRepository courses;
	@Autowired
	EnvironmentCheckRepository checks;

	FakeDocker docker = new FakeDocker();
	EnvironmentChecker checker;
	Course course;

	@BeforeEach
	void setUp() {
		JsonMapper json = JsonMapper.builder().build();
		CourseService cs = new CourseService(courses, null, null, json, null, null);
		checker = new EnvironmentChecker(docker, cs, sessions, checks, json);
		course = new Course("수업", "3.12", "six==1.16.0");
		course.setStatus(CourseStatus.READY);
		course.setEntryCode("ABC123");
		course.setBaselineJson("{\"six\":\"1.16.0\"}");
		course = courses.save(course);
	}

	private StudentSession session(String token, String containerId) {
		var s = new StudentSession(course, "학생", token, "pw");
		s.setContainerId(containerId);
		return sessions.save(s);
	}

	@Test
	void missingContainerYieldsIdeDownWithoutThrowing() {
		docker.running = false;
		var s = session("t1", "gone");
		var r = checker.checkAndRecord(s);
		assertThat(r.issues()).extracting(Issue::type).containsExactly(IssueType.IDE_DOWN);
		var fresh = sessions.findById(s.getId()).orElseThrow();
		assertThat(fresh.isContainerRunning()).isFalse();
		assertThat(fresh.getEnvStatus()).isEqualTo(EnvStatus.MISMATCH);
	}

	@Test
	void execFailureDoesNotThrow() {
		docker.throwOnExec = true;
		var r = checker.check(session("t2", "c2"));
		assertThat(r.issues()).containsExactly(new Issue(IssueType.PYTHON_MISMATCH, "python", "3.12", "error"));
	}

	@Test
	void okResultRecordedOnlyOnChange() {
		var s = session("t3", "c3");
		assertThat(checker.checkAndRecord(s).ok()).isTrue();
		var fresh = sessions.findById(s.getId()).orElseThrow();
		assertThat(fresh.getEnvStatus()).isEqualTo(EnvStatus.OK);
		assertThat(fresh.isContainerRunning()).isTrue();
		assertThat(fresh.isIdeConnected()).isFalse();
		assertThat(fresh.getLastSeenAt()).isNull();

		docker.connections = 1; // 연결 변화만으로는 행을 늘리지 않는다
		checker.checkAndRecord(s);
		assertThat(sessions.findById(s.getId()).orElseThrow().isIdeConnected()).isTrue();
		assertThat(sessions.findById(s.getId()).orElseThrow().getLastSeenAt()).isNotNull();
		assertThat(checks.findBySessionOrderByIdAsc(s)).hasSize(1);

		docker.packages = "[]";
		var r = checker.checkAndRecord(s);
		assertThat(r.signature()).isEqualTo("MISSING:six");
		assertThat(checks.findBySessionOrderByIdAsc(s)).hasSize(2);
		checker.checkAndRecord(s);
		assertThat(checks.findBySessionOrderByIdAsc(s)).hasSize(2);
	}

	@Test
	void oneSessionExecFailureDoesNotAffectNextSession() {
		var bad = session("b1", "bad");
		var good = session("b2", "good");
		docker.throwOnExecFor = "bad";
		var r1 = checker.checkAndRecord(bad);
		var r2 = checker.checkAndRecord(good);
		assertThat(r1.ok()).isFalse();
		assertThat(r2.ok()).isTrue();
		assertThat(sessions.findById(good.getId()).orElseThrow().getEnvStatus()).isEqualTo(EnvStatus.OK);
	}

	@Test
	void concurrentNeedsHelpChangeSurvivesRecord() {
		var s = session("t4", "c4");
		// 점검 도중(exec 시점)에 다른 요청이 needsHelp를 바꾼 상황
		docker.onExec = () -> {
			var other = sessions.findById(s.getId()).orElseThrow();
			other.setNeedsHelp(true);
			other.setNeedsHelpReason("학생 호출");
			sessions.saveAndFlush(other);
		};
		checker.checkAndRecord(s);
		var fresh = sessions.findById(s.getId()).orElseThrow();
		assertThat(fresh.isNeedsHelp()).isTrue();
		assertThat(fresh.getNeedsHelpReason()).isEqualTo("학생 호출");
		assertThat(fresh.getEnvStatus()).isEqualTo(EnvStatus.OK);
	}

	@Test
	void deletedSessionInsertsNoHistoryRow() {
		var s = session("t5", "c5");
		Long id = s.getId();
		sessions.delete(s);
		sessions.flush();
		checker.checkAndRecord(s);
		assertThat(checks.count()).isZero();
		assertThat(sessions.findById(id)).isEmpty();
	}

	@Test
	void commandsUseRootOwnedAbsolutePaths() {
		// PATH의 첫 항목 /opt/venv/bin은 학생(coder)이 쓸 수 있다. 거기에 uv나 cat을 두면 점검 결과를 속일 수 있다.
		checker.check(session("t6", "c6"));
		assertThat(docker.commands).containsExactlyInAnyOrder(
				List.of("/opt/venv/bin/python", "-c", "import platform;print(platform.python_version())"),
				List.of("/usr/local/bin/uv", "pip", "list", "--python", "/opt/venv/bin/python", "--format", "json"),
				List.of("/bin/cat", "/proc/net/tcp", "/proc/net/tcp6"));
	}

	static class FakeDocker implements DockerGateway {
		final List<List<String>> commands = new java.util.ArrayList<>();
		boolean running = true;
		boolean throwOnExec;
		String throwOnExecFor;
		Runnable onExec;
		int connections;
		String packages = "[{\"name\":\"six\",\"version\":\"1.16.0\"},{\"name\":\"pip\",\"version\":\"24\"}]";

		public ExecResult exec(String id, List<String> cmd, Duration t) {
			commands.add(List.copyOf(cmd));
			if (onExec != null) {
				onExec.run();
			}
			if (throwOnExec || id.equals(throwOnExecFor)) {
				throw new DockerOperationException("boom");
			}
			String c = String.join(" ", cmd);
			if (c.contains("platform")) {
				return new ExecResult(0, "3.12.7\n", "");
			}
			if (c.contains("pip list")) {
				return new ExecResult(0, packages, "");
			}
			String line = "   0: 0100007F:1F90 0100007F:D2A4 01 00000000:00000000 00:00000000 00000000  1000        0 111 1\n";
			return new ExecResult(1, "  sl  local_address\n" + line.repeat(connections), "no tcp6");
		}

		public boolean isRunning(String id) { return running; }
		public boolean imageExists(String image) { throw new UnsupportedOperationException(); }
		public String buildImage(Path d, String tag, Consumer<String> log) { throw new UnsupportedOperationException(); }
		public ExecResult runOnce(String image, List<String> a, Duration t) { throw new UnsupportedOperationException(); }
		public String createAndStart(ContainerSpec spec) { throw new UnsupportedOperationException(); }
		public int hostPort(String id, int p) { throw new UnsupportedOperationException(); }
		public ExecResult exec(String id, List<String> cmd, String w, Duration t) { return exec(id, cmd, t); }
		public void restart(String id) { }
		public void stop(String id) { }
		public void remove(String id) { }
		public void removeVolume(String v) { }
		public InputStream copyArchive(String id, String p) { throw new UnsupportedOperationException(); }
	}
}
