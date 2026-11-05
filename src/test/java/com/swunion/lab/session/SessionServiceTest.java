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
	@Autowired
	jakarta.persistence.EntityManager em;

	FakeDocker docker = new FakeDocker();
	boolean healthy = true;
	List<Integer> probedPorts = new ArrayList<>();
	Consumer<Integer> duringHealthWait = port -> { };
	SessionService service;
	Course ready;

	@BeforeEach
	void setUp() {
		var props = new LabProperties("i", "p", "m", 15, "http://lab.example.com:8080", "127.0.0.1", Path.of("."),
				"b:1", "t/course-", "swunion-test-s");
		HealthWaiter waiter = (port, timeout) -> {
			probedPorts.add(port);
			duringHealthWait.accept(port);
			return healthy;
		};
		service = SessionServices.create(sessions, courses, docker, props, waiter, JsonMapper.builder().build());
		ready = newCourse("ABC123", CourseStatus.READY);
	}

	/** 이 클래스는 PIN이 아닌 입장·컨테이너 동작을 본다. PIN 흐름은 SessionJoinPinTest가 본다. */
	private StudentSession join(String code, String name) {
		return service.join(code, name, "1234").session();
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
		StudentSession a = join("ABC123", " 철수 ");
		StudentSession b = join("ABC123", "영희");

		assertThat(a.getDisplayName()).isEqualTo("철수");
		assertThat(a.getToken()).matches("[0-9a-f]{64}").isNotEqualTo(b.getToken());
		assertThat(a.getIdePassword()).hasSize(24).matches("[A-Za-z0-9]+").isNotEqualTo(b.getIdePassword());
		assertThat(a.getEnvStatus()).isEqualTo(EnvStatus.UNKNOWN);
		assertThat(a.getIssuesJson()).isEqualTo("[]");
		assertThat(a.isNeedsHelp()).isFalse();

		ContainerSpec spec = docker.specs.get(0);
		assertThat(spec.name()).matches("swunion-test-s" + a.getId() + "-[0-9a-f]{8}");
		assertThat(spec.volumeName()).isEqualTo(spec.name()).isEqualTo(a.getVolumeName());
		assertThat(sessions.findById(a.getId()).orElseThrow().getVolumeName()).isEqualTo(spec.volumeName());
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
		StudentSession s = join("ABC123", "철수");
		assertThat(s.getStartupMillis()).isNull();
		assertThat(s.getContainerId()).isNotNull();
	}

	@Test
	void rejectsUnknownOrNotReadyCourse() {
		newCourse("BUILD1", CourseStatus.BUILDING);
		assertThatThrownBy(() -> join("ZZZZZZ", "철수")).isInstanceOf(JoinException.class);
		assertThatThrownBy(() -> join("BUILD1", "철수")).isInstanceOf(JoinException.class);
		assertThatThrownBy(() -> join(null, "철수")).isInstanceOf(JoinException.class);
		assertThat(docker.specs).isEmpty();
	}

	@Test
	void rejectsBlankOrLongName() {
		assertThatThrownBy(() -> join("ABC123", "")).isInstanceOf(JoinException.class);
		assertThatThrownBy(() -> join("ABC123", "   ")).isInstanceOf(JoinException.class);
		assertThatThrownBy(() -> join("ABC123", null)).isInstanceOf(JoinException.class);
		assertThatThrownBy(() -> join("ABC123", "가".repeat(31))).isInstanceOf(JoinException.class);
		assertThat(join("ABC123", "가".repeat(30)).getId()).isNotNull();
	}

	@Test
	void createFailureDeletesRowAndRemovesVolume() {
		docker.failCreate = true;
		assertThatThrownBy(() -> join("ABC123", "철수")).isInstanceOf(JoinException.class)
				.hasMessageContaining("컨테이너를 만들지 못했습니다");
		assertThat(sessions.count()).isZero();
		// 컨테이너는 만들어지지 않았으므로 id로 지울 것이 없고, 이 join 전용 볼륨만 지운다.
		assertThat(docker.removed).isEmpty();
		assertThat(docker.removedVolumes).singleElement().asString().matches("swunion-test-s\\d+-[0-9a-f]{8}");
	}

	@Test
	void failureAfterCreateRemovesContainerByIdAndVolume() {
		docker.failHostPort = true;
		assertThatThrownBy(() -> join("ABC123", "철수")).isInstanceOf(JoinException.class);
		assertThat(sessions.count()).isZero();
		assertThat(docker.removed).containsExactly("c" + docker.specs.get(0).labels().get("swunion.session"));
		assertThat(docker.removedVolumes).containsExactly(docker.specs.get(0).volumeName());
	}

	@Test
	void sameSessionIdAfterIdResetGetsDifferentNames() {
		StudentSession first = join("ABC123", "철수");
		long firstId = first.getId();
		String firstName = first.getVolumeName();
		sessions.deleteAll();
		sessions.flush();
		em.createNativeQuery("ALTER TABLE student_session ALTER COLUMN id RESTART WITH " + firstId).executeUpdate();
		StudentSession second = join("ABC123", "영희");
		assertThat(second.getId()).isEqualTo(firstId);
		assertThat(second.getVolumeName()).isNotEqualTo(firstName);
		assertThat(docker.specs.get(1).name()).isNotEqualTo(docker.specs.get(0).name());
	}

	@Test
	void specToStringMasksEnvValues() {
		StudentSession s = join("ABC123", "철수");
		assertThat(docker.specs.get(0).toString()).contains("PASSWORD=***").doesNotContain(s.getIdePassword());
	}

	@Test
	void byTokenAndNeedsHelp() {
		StudentSession s = join("ABC123", "철수");
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
		StudentSession s = join("ABC123", "철수");
		assertThat(service.ideUrl(s)).isEqualTo("http://lab.example.com:" + s.getHostPort() + "/");
	}

	@Test
	void restartPersistsNewHostPort() {
		StudentSession s = join("ABC123", "철수");
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

	@Test
	void restartDoesNotClobberNeedsHelpSetWhileRestarting() {
		StudentSession s = join("ABC123", "철수");
		// 재시작을 기다리는 동안 다른 요청(학생 호출)이 needsHelp를 바꾼 상황
		docker.onRestart = () -> service.flagNeedsHelp(s.getId(), "학생 호출");

		service.restartContainer(s.getId());

		StudentSession fresh = sessions.findById(s.getId()).orElseThrow();
		assertThat(fresh.isNeedsHelp()).isTrue();
		assertThat(fresh.getNeedsHelpReason()).isEqualTo("학생 호출");
		assertThat(fresh.isContainerRunning()).isTrue();
	}

	@Test
	void agentWritesTouchOnlyTheirOwnColumns() {
		StudentSession s = join("ABC123", "철수");
		service.markRepairing(s.getId());
		assertThat(sessions.findById(s.getId()).orElseThrow().getEnvStatus()).isEqualTo(EnvStatus.REPAIRING);

		service.flagNeedsHelp(s.getId(), "재점검 실패: MISSING:six", "MISSING:six");
		StudentSession fresh = sessions.findById(s.getId()).orElseThrow();
		assertThat(fresh.isNeedsHelp()).isTrue();
		assertThat(fresh.getNeedsHelpReason()).isEqualTo("재점검 실패: MISSING:six");
		assertThat(fresh.getLastEscalatedSignature()).isEqualTo("MISSING:six");
		assertThat(fresh.getEnvStatus()).as("다른 컬럼은 그대로").isEqualTo(EnvStatus.REPAIRING);
		assertThat(fresh.getHostPort()).isEqualTo(s.getHostPort());

		assertThat(sessions.clearLastEscalatedSignature(s.getId())).isEqualTo(1);
		assertThat(sessions.findById(s.getId()).orElseThrow().getLastEscalatedSignature()).isNull();
		assertThat(sessions.findById(s.getId()).orElseThrow().isNeedsHelp()).isTrue();
		// 이미 비어 있으면 갱신할 행이 없다
		assertThat(sessions.clearLastEscalatedSignature(s.getId())).isZero();
	}

	@Test
	void joinDoesNotOverwriteStateWrittenDuringHealthWait() {
		// code-server를 기다리는 동안 점검(15초 주기)과 에이전트가 같은 행을 쓴 상황
		duringHealthWait = port -> {
			long id = sessions.findAll().stream().filter(x -> port.equals(x.getHostPort())).findFirst().orElseThrow()
					.getId();
			sessions.updateCheckState(id, true, true, EnvStatus.MISMATCH, "[{\"type\":\"MISSING\"}]", "[]");
			sessions.updateEscalation(id, "재점검 실패: MISSING:six", "MISSING:six");
		};

		StudentSession s = join("ABC123", "철수");

		StudentSession fresh = sessions.findById(s.getId()).orElseThrow();
		assertThat(fresh.getStartupMillis()).isNotNull();
		assertThat(fresh.getEnvStatus()).isEqualTo(EnvStatus.MISMATCH);
		assertThat(fresh.getIssuesJson()).contains("MISSING");
		assertThat(fresh.isIdeConnected()).isTrue();
		assertThat(fresh.isNeedsHelp()).isTrue();
		assertThat(fresh.getNeedsHelpReason()).isEqualTo("재점검 실패: MISSING:six");
		assertThat(fresh.getLastEscalatedSignature()).isEqualTo("MISSING:six");
		assertThat(fresh.getContainerId()).isEqualTo(s.getContainerId());
		assertThat(fresh.getHostPort()).isEqualTo(s.getHostPort());
		assertThat(fresh.isContainerRunning()).isTrue();
	}

	@Test
	void joinRacingCourseEndRemovesItsOwnContainer() {
		// 입장 확인(READY) 뒤, 컨테이너를 만드는 사이에 강사가 수업을 종료한 상황
		docker.onCreate = () -> courses.updateStatusIf(ready.getId(), CourseStatus.READY, CourseStatus.ENDED);

		assertThatThrownBy(() -> join("ABC123", "철수")).isInstanceOf(JoinException.class)
				.hasMessage("종료된 수업입니다");

		String containerId = "c" + docker.specs.get(0).labels().get("swunion.session");
		assertThat(docker.stopped).containsExactly(containerId);
		assertThat(docker.removed).containsExactly(containerId);
		assertThat(docker.removedVolumes).containsExactly(docker.specs.get(0).volumeName());
		assertThat(sessions.count()).isZero();
		assertThat(probedPorts).as("종료된 수업의 IDE는 기다리지 않는다").isEmpty();
	}

	@Test
	void restartRefusedAfterCourseEnded() {
		StudentSession s = join("ABC123", "철수");
		courses.updateStatusIf(ready.getId(), CourseStatus.READY, CourseStatus.ENDED);
		probedPorts.clear();

		assertThat(service.restartContainer(s.getId())).isFalse();

		assertThat(docker.restarted).isEmpty();
		assertThat(probedPorts).isEmpty();
	}

	@Test
	void restartRecreatesAContainerThatDisappearedFromDocker() {
		StudentSession s = join("ABC123", "철수");
		String volume = s.getVolumeName();
		docker.containerExistsAnswer = false;
		probedPorts.clear();

		assertThat(service.restartContainer(s.getId())).isTrue();

		assertThat(docker.containerExistsCalls).containsExactly(s.getContainerId());
		assertThat(docker.restarted).isEmpty();
		assertThat(docker.specs).hasSize(2);
		assertThat(docker.specs.get(1).volumeName()).isEqualTo(volume);
		assertThat(docker.specs.get(1).env()).containsEntry("PASSWORD", s.getIdePassword());
		StudentSession fresh = sessions.findById(s.getId()).orElseThrow();
		assertThat(fresh.getHostPort()).isEqualTo(docker.ports.get(fresh.getContainerId()));
		assertThat(probedPorts).containsExactly(fresh.getHostPort());
	}

	@Test
	void restartReturnsFalseWithoutWaitingWhenDockerRefuses() {
		StudentSession s = join("ABC123", "철수");
		docker.restartFailure = new DockerOperationException("restart refused");
		probedPorts.clear();

		assertThat(service.restartContainer(s.getId())).isFalse();

		assertThat(docker.restarted).as("재시작을 실제로 시도했다").containsExactly(s.getContainerId());
		assertThat(probedPorts).isEmpty();
	}

	/**
	 * containerExists/volumeExists는 예외를 던지지 않고 설정한 답을 돌려주며 부른 이름을 기록한다.
	 * 던지게 두면 서비스의 catch(RuntimeException)이 삼켜 테스트가 아무것도 확인하지 못한 채 통과할 수 있다.
	 * Docker처럼 createAndStart가 없는 이름 붙은 볼륨을 빈 채로 만들므로, 그 뒤 volumeExists는 true가 된다.
	 * callLog는 모든 호출을 순서대로 남겨 "확인이 만들기보다 먼저인가"를 검증하게 한다.
	 */
	static class FakeDocker implements DockerGateway {
		final List<ContainerSpec> specs = new ArrayList<>();
		final Map<String, Integer> ports = new HashMap<>();
		final List<String> restarted = new ArrayList<>();
		final List<String> removed = new ArrayList<>();
		final List<String> removedVolumes = new ArrayList<>();
		final List<String> stopped = new ArrayList<>();
		final List<String> runningChecks = new ArrayList<>();
		final List<String> containerExistsCalls = new ArrayList<>();
		final List<String> volumeExistsCalls = new ArrayList<>();
		/** 모든 게이트웨이 호출을 "메서드:인자" 형태로 순서대로 남긴다. */
		final List<String> callLog = new ArrayList<>();
		/** createAndStart가 만든 볼륨. 처음부터 있던 볼륨은 volumeExistsAnswer로 정한다. */
		final java.util.Set<String> createdVolumes = new java.util.HashSet<>();
		Runnable onCreate = () -> { };
		Runnable onRestart = () -> { };
		boolean running = true;
		boolean containerExistsAnswer = true;
		boolean volumeExistsAnswer = true;
		RuntimeException runningFailure;
		RuntimeException restartFailure;
		RuntimeException createFailure;
		/** createFailure가 컨테이너 생성 뒤 시작 단계에서 난 실패를 흉내 낸다: Docker가 빈 볼륨을 이미 만들어 둔 상태. */
		boolean createFailureLeavesVolume;
		boolean failCreate;
		boolean failHostPort;
		int nextPort = 40000;
		/** createFailure로 실패한 시도도 센다. 실패 경로 테스트가 만들기를 실제로 시도했는지 확인하는 데 쓴다. */
		int createCalls;

		/** 컨테이너를 만들거나 지우거나 멈추거나 재시작한 횟수. 읽기 전용 호출은 세지 않는다. */
		int mutationCount() {
			return specs.size() + restarted.size() + removed.size() + removedVolumes.size() + stopped.size();
		}

		/** 어떤 종류든 게이트웨이를 한 번이라도 불렀는가. */
		boolean touched() {
			return !callLog.isEmpty();
		}

		public String createAndStart(ContainerSpec spec) {
			createCalls++;
			callLog.add("createAndStart:" + spec.volumeName());
			if (createFailure != null) {
				if (createFailureLeavesVolume) {
					createdVolumes.add(spec.volumeName());
				}
				throw createFailure;
			}
			if (failCreate) {
				throw new DockerOperationException("boom");
			}
			createdVolumes.add(spec.volumeName());
			specs.add(spec);
			onCreate.run();
			String id = "c" + spec.labels().get("swunion.session");
			ports.put(id, nextPort++);
			return id;
		}

		public int hostPort(String id, int containerPort) {
			callLog.add("hostPort:" + id);
			if (failHostPort) {
				throw new DockerOperationException("no port");
			}
			return ports.get(id);
		}

		public void restart(String id) {
			callLog.add("restart:" + id);
			restarted.add(id);
			if (restartFailure != null) {
				throw restartFailure;
			}
			onRestart.run();
		}
		public boolean isRunning(String id) {
			callLog.add("isRunning:" + id);
			runningChecks.add(id);
			if (runningFailure != null) {
				throw runningFailure;
			}
			return running;
		}
		public boolean containerExists(String id) {
			callLog.add("containerExists:" + id);
			containerExistsCalls.add(id);
			return containerExistsAnswer;
		}
		public boolean volumeExists(String v) {
			callLog.add("volumeExists:" + v);
			volumeExistsCalls.add(v);
			return createdVolumes.contains(v) || volumeExistsAnswer;
		}
		public void remove(String id) { callLog.add("remove:" + id); removed.add(id); }
		public boolean imageExists(String image) { return true; }
		public String buildImage(Path d, String t, Consumer<String> l) { throw new UnsupportedOperationException(); }
		public ExecResult runOnce(String i, List<String> c, Duration t) { throw new UnsupportedOperationException(); }
		public ExecResult exec(String id, List<String> c, Duration t) { throw new UnsupportedOperationException(); }
		public ExecResult exec(String id, List<String> c, String w, Duration t) { throw new UnsupportedOperationException(); }
		public void stop(String id) { callLog.add("stop:" + id); stopped.add(id); }
		public void removeVolume(String v) { callLog.add("removeVolume:" + v); removedVolumes.add(v); }
		public void removeImage(String image) { }
		public InputStream copyArchive(String id, String p) { throw new UnsupportedOperationException(); }
	}
}
