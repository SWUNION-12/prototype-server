package com.swunion.lab.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

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
	ContainerSpecs specs;
	ContainerEnsurer ensurer;
	Course course;
	StudentSession session;

	@BeforeEach
	void setUp() {
		var props = new LabProperties("i", "p", "m", 15, "http://lab.example.com:8080", "127.0.0.1", Path.of("."),
				"b:1", "t/course-", "swunion-test-s", "http://host.docker.internal:8080");
		specs = new ContainerSpecs(props);
		ensurer = ensurerOn(sessions);
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

	private ContainerEnsurer ensurerOn(SessionRepository repository) {
		return ensurerAt(repository, NOW);
	}

	private ContainerEnsurer ensurerAt(SessionRepository repository, Instant now) {
		return new ContainerEnsurer(repository, docker, specs, Clock.fixed(now, ZoneOffset.UTC));
	}

	/** 실제 저장소에 위임하되 일부 호출만 DB 오류로 바꿀 수 있는 저장소. */
	private SessionRepository repositoryThatCanFail() {
		return mock(SessionRepository.class, delegatesTo(sessions));
	}

	private StudentSession fresh() {
		return sessions.findById(session.getId()).orElseThrow();
	}

	/**
	 * createAndStart는 없는 볼륨을 빈 채로 만든다(가짜도 그렇게 동작한다). 확인이 만들기 뒤로 가면 항상 "있음"이
	 * 되어 유실을 못 보므로, 확인이 먼저인 순서 자체를 고정한다.
	 */
	private void assertVolumeCheckedBeforeCreate() {
		int check = docker.callLog.indexOf("volumeExists:" + VOLUME);
		int create = docker.callLog.indexOf("createAndStart:" + VOLUME);
		assertThat(check).as("volumeExists를 불렀다: %s", docker.callLog).isGreaterThanOrEqualTo(0);
		assertThat(create).as("createAndStart를 불렀다: %s", docker.callLog).isGreaterThanOrEqualTo(0);
		assertThat(check).as("volumeExists가 createAndStart보다 먼저: %s", docker.callLog).isLessThan(create);
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
		assertVolumeCheckedBeforeCreate();
		assertThat(docker.specs).hasSize(1);
		ContainerSpec spec = docker.specs.get(0);
		// join과 같은 이름·볼륨·라벨·환경이어야 점검과 정리가 이 컨테이너를 같은 학생 것으로 안다.
		assertThat(spec.volumeName()).isEqualTo(session.getVolumeName());
		assertThat(spec.name()).isEqualTo(session.getVolumeName());
		assertThat(spec.image()).isEqualTo("t/course-1:1");
		// 이 세션은 키 없는 예전 행이라 SWUNION_IDE_KEY가 없다. 서버 주소는 모든 컨테이너에 들어간다.
		assertThat(spec.env()).containsOnly(java.util.Map.entry("PASSWORD", "pw-keep-24-chars-ABCDEF"),
				java.util.Map.entry("SWUNION_SERVER_URL", "http://host.docker.internal:8080"),
				java.util.Map.entry("CODE_SERVER_COOKIE_SUFFIX", session.getVolumeName()));
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
		assertVolumeCheckedBeforeCreate();
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
	void recreateWithoutHostPortRemovesTheContainerAndStillRecordsTheWorkspaceLoss() {
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
		// createAndStart가 빈 볼륨을 이미 만들었다. 다음 시도는 볼륨이 있다고 보므로 유실은 지금 기록해야 한다.
		assertThat(s.getWorkspaceLostAt()).isEqualTo(NOW);
		assertThat(s.isNeedsHelp()).isTrue();
		assertThat(s.getNeedsHelpReason()).isEqualTo("작업 공간 유실");

		// 다음 시도는 (빈) 볼륨이 있어 RECREATED로 보이지만 앞서 기록한 유실은 남아 있다.
		docker.failHostPort = false;
		EnsureResult retry = ensurer.ensureRunning(session);

		assertThat(retry.outcome()).isEqualTo(EnsureOutcome.RECREATED);
		StudentSession again = fresh();
		assertThat(again.getWorkspaceLostAt()).isEqualTo(NOW);
		assertThat(again.getNeedsHelpReason()).isEqualTo("작업 공간 유실");
		assertThat(again.getContainerId()).isEqualTo("c" + session.getId());
	}

	@Test
	void failureRecordingTheContainerRemovesItAndStillRecordsTheWorkspaceLoss() {
		docker.running = false;
		docker.containerExistsAnswer = false;
		docker.volumeExistsAnswer = false;
		SessionRepository failing = repositoryThatCanFail();
		doThrow(new IllegalStateException("db down")).when(failing).updateContainerCreated(anyLong(), any(), anyInt());

		EnsureResult r = ensurerOn(failing).ensureRunning(session);

		assertThat(r.outcome()).isEqualTo(EnsureOutcome.FAILED);
		assertThat(docker.createCalls).isEqualTo(1);
		// 기록되지 않은 채 같은 이름으로 돌고 있으면 다음 시도가 이름 충돌로 막힌다.
		assertThat(docker.removed).containsExactly("c" + session.getId());
		StudentSession s = fresh();
		assertThat(s.getContainerId()).isEqualTo(OLD_CONTAINER);
		assertThat(s.getWorkspaceLostAt()).isEqualTo(NOW);
		assertThat(s.isNeedsHelp()).isTrue();
		assertThat(s.getNeedsHelpReason()).isEqualTo("작업 공간 유실");
		// 유실을 기록한 시도의 컨테이너 기록이 실패했을 뿐이므로 다음 시도에서 유실이 사라지지 않는다.
		EnsureResult retry = ensurer.ensureRunning(session);
		assertThat(retry.outcome()).isEqualTo(EnsureOutcome.RECREATED);
		assertThat(fresh().getWorkspaceLostAt()).isEqualTo(NOW);
	}

	@Test
	void failureRecordingTheLossCreatesNoContainer() {
		docker.running = false;
		docker.containerExistsAnswer = false;
		docker.volumeExistsAnswer = false;
		SessionRepository failing = repositoryThatCanFail();
		doThrow(new IllegalStateException("db down")).when(failing).markWorkspaceLost(anyLong(), any());

		EnsureResult r = ensurerOn(failing).ensureRunning(session);

		// 유실을 기록하지 못하면 빈 볼륨을 만들어 버리지 않는다. 만들면 다음 시도가 유실을 영영 알 수 없다.
		assertThat(r.outcome()).isEqualTo(EnsureOutcome.FAILED);
		assertThat(docker.volumeExistsCalls).containsExactly(VOLUME);
		assertThat(docker.createCalls).isZero();
		assertThat(docker.removed).isEmpty();
		assertThat(fresh().getContainerId()).isEqualTo(OLD_CONTAINER);
	}

	@Test
	void volumeMissingAndStartFailsStillRecordsTheLossOnce() {
		docker.running = false;
		docker.containerExistsAnswer = false;
		docker.volumeExistsAnswer = false;
		// 컨테이너가 만들어지면서 Docker가 빈 볼륨을 만들고, 시작에서 실패해 게이트웨이가 컨테이너를 치운 상태
		docker.createFailure = new IllegalStateException("start failed");
		docker.createFailureLeavesVolume = true;

		EnsureResult r = ensurer.ensureRunning(session);

		assertThat(r.outcome()).isEqualTo(EnsureOutcome.FAILED);
		assertThat(docker.createCalls).as("만들기를 실제로 시도했다").isEqualTo(1);
		assertThat(docker.removed).as("만들어진 컨테이너가 없으므로 지울 것도 없다").isEmpty();
		assertThat(docker.removedVolumes).isEmpty();
		StudentSession s = fresh();
		assertThat(s.getContainerId()).isEqualTo(OLD_CONTAINER);
		assertThat(s.getWorkspaceLostAt()).isEqualTo(NOW);
		assertThat(s.isNeedsHelp()).isTrue();
		assertThat(s.getNeedsHelpReason()).isEqualTo("작업 공간 유실");

		// 다음 시도: 빈 볼륨이 이제 있어 RECREATED로 보이지만 유실은 처음 기록한 그대로이고 이유는 한 번만 있다.
		docker.createFailure = null;
		EnsureResult retry = ensurerAt(sessions, NOW.plusSeconds(60)).ensureRunning(session);

		assertThat(retry.outcome()).isEqualTo(EnsureOutcome.RECREATED);
		StudentSession again = fresh();
		assertThat(again.getWorkspaceLostAt()).as("처음 기록한 시각을 유지한다").isEqualTo(NOW);
		assertThat(again.getNeedsHelpReason()).isEqualTo("작업 공간 유실");
		assertThat(again.getContainerId()).isEqualTo("c" + session.getId());
	}

	@Test
	void retryAfterFailedRecreateKeepsTheFirstLossRecord() {
		docker.running = false;
		docker.containerExistsAnswer = false;
		docker.volumeExistsAnswer = false;
		docker.createFailure = new IllegalStateException("name conflict");

		assertThat(ensurer.ensureRunning(session).outcome()).isEqualTo(EnsureOutcome.FAILED);
		// 이름 충돌로 볼륨은 아직 만들어지지 않았다. 다시 시도해도 볼륨이 없다.
		docker.createFailure = null;
		EnsureResult retry = ensurerAt(sessions, NOW.plusSeconds(60)).ensureRunning(session);

		assertThat(retry.outcome()).isEqualTo(EnsureOutcome.RECREATED_EMPTY);
		assertThat(docker.volumeExistsCalls).containsExactly(VOLUME, VOLUME);
		StudentSession s = fresh();
		assertThat(s.getNeedsHelpReason()).isEqualTo("작업 공간 유실");
		assertThat(s.getWorkspaceLostAt()).isEqualTo(NOW);
	}

	@Test
	void instructorClearingTheFlagIsNotUndoneByALaterAttempt() {
		docker.running = false;
		docker.containerExistsAnswer = false;
		docker.volumeExistsAnswer = false;
		docker.createFailure = new IllegalStateException("name conflict");
		ensurer.ensureRunning(session);
		assertThat(fresh().isNeedsHelp()).isTrue();
		// 강사가 확인하고 표시를 지웠다
		sessions.updateNeedsHelp(session.getId(), false, null);

		docker.createFailure = null;
		EnsureResult retry = ensurerAt(sessions, NOW.plusSeconds(60)).ensureRunning(session);

		assertThat(retry.outcome()).isEqualTo(EnsureOutcome.RECREATED_EMPTY);
		StudentSession s = fresh();
		assertThat(s.isNeedsHelp()).as("이미 기록된 유실은 강사 표시를 다시 켜지 않는다").isFalse();
		assertThat(s.getNeedsHelpReason()).isNull();
		assertThat(s.getWorkspaceLostAt()).isEqualTo(NOW);
	}

	@Test
	void sessionDeletedWhileRecreatingLeavesNoContainerBehind() {
		docker.running = false;
		docker.containerExistsAnswer = false;
		docker.onCreate = () -> sessions.deleteById(session.getId());

		EnsureResult r = ensurer.ensureRunning(session);

		assertThat(r.outcome()).isEqualTo(EnsureOutcome.FAILED);
		assertThat(docker.removed).containsExactly("c" + session.getId());
		assertThat(docker.removedVolumes).as("처음부터 있던 볼륨은 학생 코드라 지우지 않는다").isEmpty();
	}

	@Test
	void sessionDeletedWhileRecreatingOnALostVolumeAlsoRemovesTheEmptyVolumeItCreated() {
		// 볼륨이 없어 createAndStart가 빈 볼륨을 새로 만들었는데, 그 사이 세션 행이 지워졌다. 학생 삭제는 이미 끝나
		// 이 볼륨을 다시 지울 쪽이 없다.
		docker.running = false;
		docker.containerExistsAnswer = false;
		docker.volumeExistsAnswer = false;
		docker.onCreate = () -> sessions.deleteById(session.getId());

		EnsureResult r = ensurer.ensureRunning(session);

		assertThat(r.outcome()).isEqualTo(EnsureOutcome.FAILED);
		String newContainer = "c" + session.getId();
		assertThat(docker.removed).containsExactly(newContainer);
		assertThat(docker.removedVolumes).containsExactly(VOLUME);
		// 컨테이너가 붙은 볼륨은 지워지지 않으므로 컨테이너가 먼저다.
		assertThat(docker.callLog).containsSubsequence("remove:" + newContainer, "removeVolume:" + VOLUME);
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
