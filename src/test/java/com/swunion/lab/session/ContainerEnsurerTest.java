package com.swunion.lab.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.swunion.lab.config.LabProperties;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.docker.ContainerSpec;
import com.swunion.lab.docker.DockerOperationException;
import com.swunion.lab.session.ContainerEnsurer.EnsureOutcome;
import com.swunion.lab.session.ContainerEnsurer.EnsureResult;
import com.swunion.lab.session.SessionServiceTest.FakeDocker;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class ContainerEnsurerTest {

	static final Instant NOW = Instant.parse("2026-11-05T09:30:00Z");
	static final String OLD_CONTAINER = "old-container";
	static final String VOLUME = "swunion-test-s1-aaaa1111";

	@Autowired
	SessionRepository sessions;
	@Autowired
	CourseRepository courses;

	FakeDocker docker = new FakeDocker();
	ContainerEnsurer ensurer;
	Course course;
	StudentSession session;

	@BeforeEach
	void setUp() {
		var props = new LabProperties("i", "p", "m", 15, "http://lab.example.com:8080", "127.0.0.1", Path.of("."),
				"b:1", "t/course-", "swunion-test-s");
		ensurer = new ContainerEnsurer(sessions, docker, new ContainerSpecs(props),
				Clock.fixed(NOW, ZoneOffset.UTC));
		Course c = new Course("수업", "3.12", "six==1.16.0");
		c.setStatus(CourseStatus.READY);
		c.setEntryCode("ENS001");
		c.setImageName("t/course-1:1");
		course = courses.save(c);
		// 입장이 끝난 뒤의 세션: 컨테이너 기록과 볼륨 이름이 있고 지금은 꺼져 있다.
		StudentSession s = new StudentSession(course, "철수", "tok-ensure", "pw-keep-24-chars-ABCDEF");
		s.setContainerId(OLD_CONTAINER);
		s.setVolumeName(VOLUME);
		s.setHostPort(41000);
		s.setContainerRunning(false);
		session = sessions.saveAndFlush(s);
		docker.ports.put(OLD_CONTAINER, 41000);
	}

	private StudentSession fresh() {
		return sessions.findById(session.getId()).orElseThrow();
	}

	@Test
	void runningContainerIsLeftAlone() {
		docker.running = true;

		EnsureResult r = ensurer.ensureRunning(session);

		assertThat(r.outcome()).isEqualTo(EnsureOutcome.ALREADY_RUNNING);
		assertThat(r.hostPort()).isEqualTo(41000);
		assertThat(docker.runningChecks).containsExactly(OLD_CONTAINER);
		assertThat(docker.mutationCount()).as("변경 호출 0회").isZero();
		assertThat(docker.volumeExistsCalls).isEmpty();
	}

	@Test
	void stoppedContainerIsRestartedAndRunningFlagStored() {
		docker.running = false;
		docker.containerExistsAnswer = true;

		EnsureResult r = ensurer.ensureRunning(session);

		assertThat(r.outcome()).isEqualTo(EnsureOutcome.STARTED);
		assertThat(r.hostPort()).isEqualTo(41000);
		assertThat(docker.restarted).containsExactly(OLD_CONTAINER);
		assertThat(docker.containerExistsCalls).containsExactly(OLD_CONTAINER);
		assertThat(docker.specs).as("있는 컨테이너는 다시 만들지 않는다").isEmpty();
		// 컨테이너가 볼륨을 붙들고 있으므로 시작 경로에서는 볼륨을 따로 확인하지 않는다.
		assertThat(docker.volumeExistsCalls).isEmpty();
		StudentSession s = fresh();
		assertThat(s.isContainerRunning()).isTrue();
		assertThat(s.getHostPort()).isEqualTo(41000);
		assertThat(s.getContainerId()).isEqualTo(OLD_CONTAINER);
		assertThat(s.getWorkspaceLostAt()).isNull();
	}

	@Test
	void hostPortChangeAfterRestartIsStored() {
		docker.running = false;
		docker.onRestart = () -> docker.ports.put(OLD_CONTAINER, 41007);

		EnsureResult r = ensurer.ensureRunning(session);

		assertThat(r.outcome()).isEqualTo(EnsureOutcome.STARTED);
		assertThat(r.hostPort()).isEqualTo(41007);
		assertThat(fresh().getHostPort()).isEqualTo(41007);
		assertThat(docker.restarted).containsExactly(OLD_CONTAINER);
	}

	@Test
	void missingContainerIsRecreatedOnTheSameVolume() {
		docker.running = false;
		docker.containerExistsAnswer = false;
		docker.volumeExistsAnswer = true;

		EnsureResult r = ensurer.ensureRunning(session);

		assertThat(r.outcome()).isEqualTo(EnsureOutcome.RECREATED);
		assertThat(docker.containerExistsCalls).containsExactly(OLD_CONTAINER);
		assertThat(docker.volumeExistsCalls).containsExactly(VOLUME);
		assertThat(docker.specs).hasSize(1);
		ContainerSpec spec = docker.specs.get(0);
		// join과 같은 이름·볼륨·라벨·환경이어야 점검과 정리가 이 컨테이너를 같은 학생 것으로 안다.
		assertThat(spec.volumeName()).isEqualTo(session.getVolumeName());
		assertThat(spec.name()).isEqualTo(session.getVolumeName());
		assertThat(spec.image()).isEqualTo("t/course-1:1");
		assertThat(spec.env()).containsOnly(java.util.Map.entry("PASSWORD", "pw-keep-24-chars-ABCDEF"));
		assertThat(spec.bindAddress()).isEqualTo("127.0.0.1");
		assertThat(spec.containerPort()).isEqualTo(8080);
		assertThat(spec.volumeMountPath()).isEqualTo("/home/coder/project");
		assertThat(spec.labels()).containsOnly(java.util.Map.entry("swunion.session", String.valueOf(session.getId())),
				java.util.Map.entry("swunion.course", String.valueOf(course.getId())));

		String newId = "c" + session.getId();
		assertThat(r.hostPort()).isEqualTo(docker.ports.get(newId));
		StudentSession s = fresh();
		assertThat(s.getContainerId()).isEqualTo(newId).isNotEqualTo(OLD_CONTAINER);
		assertThat(s.getHostPort()).isEqualTo(r.hostPort());
		assertThat(s.isContainerRunning()).isTrue();
		assertThat(s.getVolumeName()).isEqualTo(VOLUME);
		assertThat(s.getIdePassword()).isEqualTo("pw-keep-24-chars-ABCDEF");
		assertThat(s.getWorkspaceLostAt()).isNull();
		assertThat(s.isNeedsHelp()).isFalse();
		// 이름으로 무언가를 지우지 않는다.
		assertThat(docker.removed).isEmpty();
		assertThat(docker.removedVolumes).isEmpty();
		assertThat(docker.restarted).isEmpty();
	}

	@Test
	void missingContainerAndVolumeRecreatesEmptyAndFlagsInstructor() {
		docker.running = false;
		docker.containerExistsAnswer = false;
		docker.volumeExistsAnswer = false;

		EnsureResult r = ensurer.ensureRunning(session);

		assertThat(r.outcome()).isEqualTo(EnsureOutcome.RECREATED_EMPTY);
		assertThat(docker.volumeExistsCalls).containsExactly(VOLUME);
		assertThat(docker.specs).hasSize(1);
		assertThat(docker.specs.get(0).volumeName()).isEqualTo(VOLUME);
		StudentSession s = fresh();
		assertThat(s.getWorkspaceLostAt()).isEqualTo(NOW);
		assertThat(s.isNeedsHelp()).isTrue();
		assertThat(s.getNeedsHelpReason()).isEqualTo("작업 공간 유실");
		assertThat(s.getContainerId()).isEqualTo("c" + session.getId());
		assertThat(s.isContainerRunning()).isTrue();
		assertThat(docker.removed).isEmpty();
		assertThat(docker.removedVolumes).isEmpty();
	}

	@Test
	void workspaceLossAppendsToAnEarlierReasonInsteadOfReplacingIt() {
		sessions.updateNeedsHelp(session.getId(), true, "재점검 실패: MISSING:six");
		docker.running = false;
		docker.containerExistsAnswer = false;
		docker.volumeExistsAnswer = false;

		ensurer.ensureRunning(session);

		assertThat(fresh().getNeedsHelpReason()).isEqualTo("재점검 실패: MISSING:six / 작업 공간 유실");
	}

	@Test
	void restartFailureIsReportedNotThrown() {
		docker.running = false;
		docker.restartFailure = new DockerOperationException("restart refused");

		EnsureResult[] result = new EnsureResult[1];
		assertThatCode(() -> result[0] = ensurer.ensureRunning(session)).doesNotThrowAnyException();

		assertThat(result[0].outcome()).isEqualTo(EnsureOutcome.FAILED);
		assertThat(result[0].hostPort()).isNull();
		assertThat(docker.restarted).as("재시작을 실제로 시도했다").containsExactly(OLD_CONTAINER);
		StudentSession s = fresh();
		assertThat(s.isContainerRunning()).as("실패하면 실행 중으로 기록하지 않는다").isFalse();
		assertThat(s.getHostPort()).isEqualTo(41000);
	}

	@Test
	void dockerLookupFailureIsReportedNotThrown() {
		docker.runningFailure = new DockerOperationException("daemon down");

		EnsureResult r = ensurer.ensureRunning(session);

		assertThat(r.outcome()).isEqualTo(EnsureOutcome.FAILED);
		assertThat(docker.runningChecks).containsExactly(OLD_CONTAINER);
		assertThat(docker.mutationCount()).isZero();
	}

	@Test
	void nameConflictOnRecreateFailsWithoutRemovingAnything() {
		docker.running = false;
		docker.containerExistsAnswer = false;
		// docker-java는 이름 충돌을 DockerOperationException이 아닌 자체 예외로 던진다.
		docker.createFailure = new IllegalStateException("409 Conflict: name already in use");

		EnsureResult r = ensurer.ensureRunning(session);

		assertThat(r.outcome()).isEqualTo(EnsureOutcome.FAILED);
		assertThat(docker.createCalls).as("만들기를 실제로 시도했다").isEqualTo(1);
		assertThat(docker.specs).isEmpty();
		assertThat(docker.removed).isEmpty();
		assertThat(docker.removedVolumes).isEmpty();
		assertThat(docker.stopped).isEmpty();
		StudentSession s = fresh();
		assertThat(s.getContainerId()).isEqualTo(OLD_CONTAINER);
		assertThat(s.getWorkspaceLostAt()).isNull();
		assertThat(s.isNeedsHelp()).isFalse();
	}

	@Test
	void recreateWithoutHostPortRemovesTheContainerItJustMadeById() {
		docker.running = false;
		docker.containerExistsAnswer = false;
		docker.volumeExistsAnswer = false;
		docker.failHostPort = true;

		EnsureResult r = ensurer.ensureRunning(session);

		assertThat(r.outcome()).isEqualTo(EnsureOutcome.FAILED);
		assertThat(docker.specs).hasSize(1);
		assertThat(docker.removed).containsExactly("c" + session.getId());
		assertThat(docker.removedVolumes).as("볼륨은 학생 코드라 지우지 않는다").isEmpty();
		StudentSession s = fresh();
		assertThat(s.getContainerId()).isEqualTo(OLD_CONTAINER);
		// 기록이 바뀌지 않았으니 유실도 기록하지 않는다. 다음 시도가 다시 판단한다.
		assertThat(s.getWorkspaceLostAt()).isNull();
		assertThat(s.isNeedsHelp()).isFalse();
	}

	@Test
	void sessionDeletedWhileRecreatingLeavesNoContainerBehind() {
		docker.running = false;
		docker.containerExistsAnswer = false;
		docker.onCreate = () -> sessions.deleteById(session.getId());

		EnsureResult r = ensurer.ensureRunning(session);

		assertThat(r.outcome()).isEqualTo(EnsureOutcome.FAILED);
		assertThat(docker.removed).containsExactly("c" + session.getId());
	}

	@Test
	void sessionWithNoContainerAndNoVolumeFailsWithoutTouchingDocker() {
		// 입장이 실패해 남은 행처럼 만들 것이 아무것도 없는 세션
		StudentSession bare = sessions.saveAndFlush(new StudentSession(course, "영희", "tok-bare", "pw"));

		EnsureResult r = ensurer.ensureRunning(bare);

		assertThat(r.outcome()).isEqualTo(EnsureOutcome.FAILED);
		assertThat(r.hostPort()).isNull();
		assertThat(docker.touched()).isFalse();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = " ")
	void missingContainerIdWithVolumeIsTreatedAsMissingContainer(String containerId) {
		StudentSession s = new StudentSession(course, "영희", "tok-novolume-ctr", "pw-two");
		s.setContainerId(containerId);
		s.setVolumeName("swunion-test-s2-bbbb2222");
		s = sessions.saveAndFlush(s);

		EnsureResult r = ensurer.ensureRunning(s);

		assertThat(r.outcome()).isEqualTo(EnsureOutcome.RECREATED);
		// 컨테이너 id가 없으면 존재 여부를 물을 대상이 없다. null이나 빈 이름으로 묻지 않는다.
		assertThat(docker.runningChecks).isEmpty();
		assertThat(docker.containerExistsCalls).isEmpty();
		assertThat(docker.volumeExistsCalls).containsExactly("swunion-test-s2-bbbb2222");
		assertThat(docker.specs).singleElement().satisfies(spec -> {
			assertThat(spec.volumeName()).isEqualTo("swunion-test-s2-bbbb2222");
			assertThat(spec.env()).containsEntry("PASSWORD", "pw-two");
		});
		assertThat(sessions.findById(s.getId()).orElseThrow().getContainerId()).isEqualTo("c" + s.getId());
	}

	@Test
	void missingContainerIdAndLostVolumeRecreatesEmpty() {
		StudentSession s = new StudentSession(course, "영희", "tok-lost-both", "pw-three");
		s.setVolumeName("swunion-test-s3-cccc3333");
		s = sessions.saveAndFlush(s);
		docker.volumeExistsAnswer = false;

		EnsureResult r = ensurer.ensureRunning(s);

		assertThat(r.outcome()).isEqualTo(EnsureOutcome.RECREATED_EMPTY);
		assertThat(docker.containerExistsCalls).isEmpty();
		StudentSession fresh = sessions.findById(s.getId()).orElseThrow();
		assertThat(fresh.getWorkspaceLostAt()).isEqualTo(NOW);
		assertThat(fresh.getNeedsHelpReason()).isEqualTo("작업 공간 유실");
	}

	@Test
	void containerWithoutVolumeNameCannotBeRecreated() {
		StudentSession s = new StudentSession(course, "영희", "tok-no-vol", "pw-four");
		s.setContainerId("gone-container");
		s = sessions.saveAndFlush(s);
		docker.running = false;
		docker.containerExistsAnswer = false;

		EnsureResult r = ensurer.ensureRunning(s);

		assertThat(r.outcome()).isEqualTo(EnsureOutcome.FAILED);
		assertThat(docker.containerExistsCalls).containsExactly("gone-container");
		assertThat(docker.volumeExistsCalls).as("빈 이름으로 묻지 않는다").isEmpty();
		assertThat(docker.specs).isEmpty();
	}

	@Test
	void restartRestartsEvenWhenContainerIsRunning() {
		docker.running = true;
		docker.onRestart = () -> docker.ports.put(OLD_CONTAINER, 41009);

		EnsureResult r = ensurer.restart(session);

		assertThat(r.outcome()).isEqualTo(EnsureOutcome.STARTED);
		assertThat(r.hostPort()).isEqualTo(41009);
		assertThat(docker.restarted).containsExactly(OLD_CONTAINER);
		assertThat(fresh().getHostPort()).isEqualTo(41009);
		assertThat(fresh().isContainerRunning()).isTrue();
	}

	@Test
	void restartRecreatesWhenContainerIsGone() {
		docker.containerExistsAnswer = false;

		EnsureResult r = ensurer.restart(session);

		assertThat(r.outcome()).isEqualTo(EnsureOutcome.RECREATED);
		assertThat(docker.restarted).isEmpty();
		assertThat(docker.specs).hasSize(1);
		assertThat(fresh().getContainerId()).isEqualTo("c" + session.getId());
	}
}
