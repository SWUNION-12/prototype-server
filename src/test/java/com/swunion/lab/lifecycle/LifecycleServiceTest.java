package com.swunion.lab.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.swunion.lab.agent.AgentActionFixtures;
import com.swunion.lab.agent.AgentActionRepository;
import com.swunion.lab.agent.AgentTrigger;
import com.swunion.lab.agent.Classification;
import com.swunion.lab.agent.Outcome;
import com.swunion.lab.agent.Trigger;
import com.swunion.lab.check.EnvironmentCheck;
import com.swunion.lab.check.EnvironmentCheckRepository;
import com.swunion.lab.config.LabProperties;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseLocks;
import com.swunion.lab.course.CourseProvisioner;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.course.CourseService;
import com.swunion.lab.course.CourseStateException;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.docker.ContainerSpec;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.docker.DockerOperationException;
import com.swunion.lab.docker.ExecResult;
import com.swunion.lab.help.HelpRequest;
import com.swunion.lab.help.HelpRequestRepository;
import com.swunion.lab.lifecycle.LifecycleService.ConfirmNameMismatchException;
import com.swunion.lab.lifecycle.LifecycleService.DeleteResult;
import com.swunion.lab.lifecycle.LifecycleService.NotFoundException;
import com.swunion.lab.lifecycle.LifecycleService.ResumeResult;
import com.swunion.lab.local.LocalInstall;
import com.swunion.lab.local.LocalInstallRepository;
import com.swunion.lab.session.ContainerEnsurer;
import com.swunion.lab.session.ContainerEnsurer.EnsureOutcome;
import com.swunion.lab.session.ContainerEnsurer.EnsureResult;
import com.swunion.lab.session.ContainerSpecs;
import com.swunion.lab.session.HealthWaiter;
import com.swunion.lab.session.SessionRepository;
import com.swunion.lab.session.SessionService;
import com.swunion.lab.session.SessionServices;
import com.swunion.lab.session.StudentSession;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.AdditionalAnswers;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 수업 재개·삭제, 학생 재시작·삭제·PIN 초기화. 재개는 다른 스레드에서 DB를 쓰므로 테스트 트랜잭션을 쓰지 않고
 * 행을 실제로 커밋한 뒤 @AfterEach에서 지운다. Docker는 상태를 가진 가짜(FakeDocker)로 흉내 내고 모든 호출을 남긴다.
 */
@DataJpaTest(properties = "lab.test.isolated-context=lifecycle-service")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(LifecycleServiceTest.RowsConfig.class)
class LifecycleServiceTest {

	static final String IMAGE_A = "t/course-a:1";
	static final String IMAGE_B = "t/course-b:1";
	/** LifecycleRows의 세션 행 삭제를 DB 오류로 바꾼다. 트랜잭션 하나로 묶였는지 보는 테스트만 켠다. */
	static final AtomicBoolean FAIL_SESSION_ROW_DELETE = new AtomicBoolean();

	@TestConfiguration
	static class RowsConfig {

		// @Transactional이 실제로 걸린 프록시 빈이어야 "한 트랜잭션"을 시험할 수 있어 Spring 빈으로 만든다.
		@Bean
		LifecycleRows lifecycleRows(AgentActionRepository actions, HelpRequestRepository helps,
				EnvironmentCheckRepository checks, LocalInstallRepository locals, SessionRepository sessions,
				CourseRepository courses) {
			Answer<Object> real = AdditionalAnswers.delegatesTo(sessions);
			SessionRepository canFail = mock(SessionRepository.class, invocation -> {
				if (FAIL_SESSION_ROW_DELETE.get() && invocation.getMethod().getName().startsWith("delete")) {
					throw new IllegalStateException("DB 오류 흉내");
				}
				return real.answer(invocation);
			});
			return new LifecycleRows(actions, helps, checks, locals, canFail, courses);
		}
	}

	@Autowired
	SessionRepository sessions;
	@Autowired
	CourseRepository courses;
	@Autowired
	AgentActionRepository actions;
	@Autowired
	HelpRequestRepository helps;
	@Autowired
	EnvironmentCheckRepository checks;
	@Autowired
	LocalInstallRepository locals;
	@Autowired
	LifecycleRows rows;
	@TempDir
	Path dataDir;

	final FakeDocker docker = new FakeDocker();
	final FakeHealth health = new FakeHealth();
	final FakeAgents agents = new FakeAgents();
	LabProperties props;
	ScriptedEnsurer ensurer;
	CourseLocks locks;
	LifecycleService service;
	int seq;

	@BeforeEach
	void setUp() {
		props = new LabProperties("i", "p", "m", 15, "http://lab.example.com:8080", "127.0.0.1", dataDir, "b:1",
				"t/course-", "swunion-test-s");
		ensurer = new ScriptedEnsurer(sessions, docker, new ContainerSpecs(props), Clock.systemUTC());
		locks = new CourseLocks(Duration.ofSeconds(5));
		service = serviceWith(Duration.ofSeconds(60));
	}

	@AfterEach
	void cleanUp() {
		FAIL_SESSION_ROW_DELETE.set(false);
		actions.deleteAll();
		helps.deleteAll();
		checks.deleteAll();
		locals.deleteAll();
		sessions.deleteAll();
		courses.deleteAll();
	}

	private LifecycleService serviceWith(Duration resumeDeadline) {
		SessionService sessionService = SessionServices.create(sessions, courses, docker, props, health,
				JsonMapper.builder().build(), Clock.systemUTC(), ensurer);
		return new LifecycleService(courses, sessions, ensurer, health, agents, docker, sessionService, rows, locks,
				props, resumeDeadline);
	}

	private CourseService courseService() {
		return new CourseService(courses, mock(CourseProvisioner.class), r -> {
		}, JsonMapper.builder().build(), sessions, docker, locks);
	}

	private Course course(String name, CourseStatus status, String image) {
		Course c = new Course(name, "3.12", "six==1.16.0");
		c.setStatus(status);
		c.setImageName(image);
		return courses.save(c);
	}

	/** 입장이 끝난 학생 행. Docker 모형에 무엇이 있는지는 각 테스트가 정한다. */
	private StudentSession student(Course c, String name, String containerId, String volume) {
		seq++;
		StudentSession s = new StudentSession(c, name, "tok-" + seq, "pw-" + seq);
		s.setContainerId(containerId);
		s.setVolumeName(volume);
		s.setHostPort(41000 + seq);
		return sessions.save(s);
	}

	/** 네 하위 표(에이전트 조치, 도움 요청, 점검 기록, 로컬 설치)에 행을 하나씩 만든다. */
	private void childRows(StudentSession s) {
		Instant t = Instant.parse("2026-11-05T09:00:00Z");
		actions.save(AgentActionFixtures.action(s, Trigger.CHECK, Classification.ENV, Outcome.RESOLVED, "[]", "요약",
				t, t));
		helps.save(new HelpRequest(s, "ModuleNotFoundError", "ModuleNotFoundError: x"));
		checks.save(new EnvironmentCheck(s, true, "[]", "[]"));
		locals.save(new LocalInstall(s));
	}

	private int childRowCount(long sessionId) {
		return actions.findBySession_IdOrderByIdAsc(sessionId).size()
				+ (int) helps.findAll().stream().filter(h -> h.getSession().getId() == sessionId).count()
				+ (int) checks.findAll().stream().filter(c -> c.getSession().getId() == sessionId).count()
				+ (locals.findBySessionId(sessionId).isPresent() ? 1 : 0);
	}

	private Path courseDir(Course c) throws IOException {
		Path dir = dataDir.resolve("courses").resolve(String.valueOf(c.getId()));
		Files.createDirectories(dir.resolve("spec"));
		Files.writeString(dir.resolve("Dockerfile"), "FROM b:1");
		Files.writeString(dir.resolve("spec").resolve("pyproject.toml"), "[project]");
		return dir;
	}

	private void assertUntouched(Course c, StudentSession s, Path dir) {
		assertThat(docker.callLog).as("Docker 호출 없음").isEmpty();
		assertThat(courses.findById(c.getId())).isPresent();
		assertThat(sessions.findById(s.getId())).isPresent();
		assertThat(childRowCount(s.getId())).isEqualTo(4);
		assertThat(dir.resolve("Dockerfile")).exists();
	}

	// ---- 수업 재개 ----

	@ParameterizedTest
	@EnumSource(value = CourseStatus.class, names = { "READY", "FAILED", "LOCKING", "BUILDING" })
	void resumeOnlyFromEnded(CourseStatus status) {
		Course c = course("수업1", status, IMAGE_A);
		student(c, "가영", "c1", "v1");
		docker.addContainer("c1", "v1", false);

		assertThatThrownBy(() -> service.resume(c.getId())).isInstanceOf(CourseStateException.class)
				.hasMessageContaining("종료된 수업만");

		assertThat(courses.findStatusById(c.getId())).contains(status);
		assertThat(ensurer.ensureCalls).isEmpty();
		assertThat(docker.callLog).isEmpty();
	}

	@Test
	void resumeUnknownCourseIsNotFound() {
		assertThatThrownBy(() -> service.resume(987654L)).isInstanceOf(NotFoundException.class);
	}

	@Test
	void resumeStartsThenReady() {
		Course c = course("수업1", CourseStatus.ENDED, IMAGE_A);
		StudentSession a = student(c, "가영", "c1", "v1");
		StudentSession b = student(c, "나리", "c2", "v2");
		StudentSession d = student(c, "다은", "c3", "v3");
		// 입장 도중 끊겨 컨테이너도 볼륨 이름도 없는 행은 켤 것이 없다.
		student(c, "라온", null, null);
		docker.addContainer("c1", "v1", false);
		docker.addContainer("c2", "v2", false);
		docker.addContainer("c3", "v3", false);
		docker.portOnStart.put("c1", 47001);
		docker.portOnStart.put("c2", 47002);
		docker.portOnStart.put("c3", 47003);
		List<CourseStatus> duringEnsure = new CopyOnWriteArrayList<>();
		List<CourseStatus> duringHealth = new CopyOnWriteArrayList<>();
		ensurer.during = s -> duringEnsure.add(courses.findStatusById(c.getId()).orElseThrow());
		health.during = port -> duringHealth.add(courses.findStatusById(c.getId()).orElseThrow());

		ResumeResult r = service.resume(c.getId());

		assertThat(r).isEqualTo(new ResumeResult(3, 0, 0, 0));
		assertThat(ensurer.ensureCalls).containsExactlyInAnyOrder(a.getId(), b.getId(), d.getId());
		assertThat(ensurer.restartCalls).isEmpty();
		assertThat(duringEnsure).as("컨테이너를 켜는 동안 수업은 ENDED").hasSize(3).containsOnly(CourseStatus.ENDED);
		assertThat(duringHealth).as("IDE를 기다리는 동안 수업은 ENDED").hasSize(3).containsOnly(CourseStatus.ENDED);
		assertThat(courses.findStatusById(c.getId())).contains(CourseStatus.READY);
		assertThat(docker.callLog).contains("restart:c1", "restart:c2", "restart:c3");
		// 켜면서 바뀐 호스트 포트를 저장하고, 그 포트로 IDE를 기다렸다.
		assertThat(sessions.findById(a.getId()).orElseThrow().getHostPort()).isEqualTo(47001);
		assertThat(sessions.findById(d.getId()).orElseThrow().isContainerRunning()).isTrue();
		assertThat(health.probedPorts).containsExactlyInAnyOrder(47001, 47002, 47003);
		assertThat(health.timeouts).allSatisfy(t -> assertThat(t).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(60)));
	}

	@Test
	void resumeCountsOutcomes() {
		Course c = course("수업1", CourseStatus.ENDED, IMAGE_A);
		student(c, "가영", "c1", "v1"); // 이미 돌고 있고 IDE도 응답한다 → started
		StudentSession lost = student(c, "나리", "c2", "v2"); // 볼륨이 없어 빈 볼륨으로 다시 만들었다 → recreatedEmpty
		StudentSession broken = student(c, "다은", "c3", "v3"); // 켜지 못했다 → failed
		student(c, "라온", "c4", "v4"); // 켰지만 IDE가 응답하지 않는다 → unhealthy
		docker.addContainer("c1", "v1", true);
		docker.addContainer("c4", "v4", false);
		docker.portOnStart.put("c4", 47004);
		ensurer.scripted.put(lost.getId(), new EnsureResult(EnsureOutcome.RECREATED_EMPTY, 47002));
		ensurer.scripted.put(broken.getId(), new EnsureResult(EnsureOutcome.FAILED, null));
		health.unhealthyPorts.add(47004);
		// 유실된 학생은 IDE가 응답하지 않아도 recreatedEmpty로만 센다.
		health.unhealthyPorts.add(47002);

		ResumeResult r = service.resume(c.getId());

		assertThat(r).isEqualTo(new ResumeResult(1, 1, 1, 1));
		assertThat(courses.findStatusById(c.getId())).as("실패가 있어도 READY").contains(CourseStatus.READY);
		assertThat(ensurer.ensureCalls).hasSize(4);
		assertThat(health.probedPorts).as("FAILED는 기다리지 않는다").hasSize(3).contains(47002, 47004);
	}

	@Test
	void resumeRunsInParallelOnABoundedPool() {
		Course c = course("수업1", CourseStatus.ENDED, IMAGE_A);
		int students = LifecycleService.RESUME_POOL_SIZE + 2;
		for (int i = 0; i < students; i++) {
			student(c, "학생" + i, "c" + i, "v" + i);
			docker.addContainer("c" + i, "v" + i, true);
		}
		CountDownLatch together = new CountDownLatch(LifecycleService.RESUME_POOL_SIZE);
		AtomicInteger inFlight = new AtomicInteger();
		AtomicInteger maxInFlight = new AtomicInteger();
		List<Boolean> metTheOthers = new CopyOnWriteArrayList<>();
		ensurer.during = s -> {
			maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
			together.countDown();
			// 차례로 돈다면 첫 학생이 나머지를 기다리다 시간이 다 된다.
			metTheOthers.add(await(together, Duration.ofSeconds(5)));
			inFlight.decrementAndGet();
		};

		ResumeResult r = service.resume(c.getId());

		assertThat(r.started()).isEqualTo(students);
		assertThat(metTheOthers).hasSize(students).containsOnly(true);
		assertThat(maxInFlight.get()).isEqualTo(LifecycleService.RESUME_POOL_SIZE);
	}

	@Test
	void resumeStopsWaitingAtTheDeadline() throws InterruptedException {
		service = serviceWith(Duration.ofSeconds(1));
		Course c = course("수업1", CourseStatus.ENDED, IMAGE_A);
		StudentSession slowIde = student(c, "가영", "c1", "v1");
		StudentSession stuckDocker = student(c, "나리", "c2", "v2");
		student(c, "다은", "c3", "v3");
		docker.addContainer("c1", "v1", true);
		docker.addContainer("c2", "v2", true);
		docker.addContainer("c3", "v3", true);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch backgroundDone = new CountDownLatch(2);
		ensurer.during = s -> {
			if (s.getId().equals(stuckDocker.getId())) {
				await(release, Duration.ofSeconds(20));
			}
		};
		ensurer.after = s -> {
			if (s.getId().equals(stuckDocker.getId())) {
				backgroundDone.countDown();
			}
		};
		health.during = port -> {
			if (port == slowIde.getHostPort()) {
				await(release, Duration.ofSeconds(20));
				backgroundDone.countDown();
			}
		};
		long t0 = System.nanoTime();
		ResumeResult r;
		Duration took;
		try {
			r = service.resume(c.getId());
			took = Duration.ofNanos(System.nanoTime() - t0);
		}
		finally {
			// 상한 뒤에도 끝까지 가는 두 시도를 풀어 주고, 정리(@AfterEach) 전에 끝나기를 기다린다.
			release.countDown();
		}

		assertThat(took).isLessThan(Duration.ofSeconds(5));
		// 상한까지 끝나지 않은 학생은 컨테이너 단계든 IDE 대기든 아직 켜는 중이라 unhealthy로 센다.
		assertThat(r).isEqualTo(new ResumeResult(1, 2, 0, 0));
		assertThat(courses.findStatusById(c.getId())).contains(CourseStatus.READY);
		assertThat(health.timeouts).allSatisfy(t -> assertThat(t).isLessThanOrEqualTo(Duration.ofSeconds(1)));
		assertThat(backgroundDone.await(10, TimeUnit.SECONDS)).as("상한 뒤의 시도도 끝났다").isTrue();
	}

	/**
	 * 상한은 응답만 끊고 진행 중인 시도는 끊지 않는다. httpclient5는 인터럽트를 받으면 Docker 호출 중간에 실패할 수 있어,
	 * 다시 만들던 컨테이너가 기록되지 않은 채 학생 볼륨 이름을 쥐고 남으면 이후 모든 재입장·재시작이 이름 충돌로 실패한다.
	 */
	@Test
	void resumeDeadlineLetsRunningAndQueuedStudentsFinishWithoutInterrupting() throws InterruptedException {
		service = serviceWith(Duration.ofSeconds(1));
		Course c = course("수업1", CourseStatus.ENDED, IMAGE_A);
		// 풀을 다 채운 학생들이 상한을 넘겨 Docker 단계에 머물고, 나머지 한 명은 큐에서 기다린다.
		int students = LifecycleService.RESUME_POOL_SIZE + 1;
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < students; i++) {
			ids.add(student(c, "학생" + i, "c" + i, "v" + i).getId());
			docker.addContainer("c" + i, "v" + i, false);
			docker.portOnStart.put("c" + i, 47100 + i);
		}
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(students);
		List<Long> interrupted = new CopyOnWriteArrayList<>();
		ensurer.during = s -> {
			try {
				release.await(20, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				interrupted.add(s.getId());
				Thread.currentThread().interrupt();
			}
		};
		ensurer.after = s -> {
			// 실제 Docker 호출(가짜 게이트웨이)까지 인터럽트 없이 갔는지 본다.
			if (Thread.currentThread().isInterrupted()) {
				interrupted.add(s.getId());
			}
			finished.countDown();
		};
		long t0 = System.nanoTime();
		ResumeResult r;
		Duration took;
		List<Long> calledByDeadline;
		try {
			r = service.resume(c.getId());
			took = Duration.ofNanos(System.nanoTime() - t0);
			calledByDeadline = List.copyOf(ensurer.ensureCalls);
		}
		finally {
			release.countDown();
		}
		boolean allFinished = finished.await(10, TimeUnit.SECONDS);

		assertThat(took).as("응답은 상한에 맞춰 돌아온다").isLessThan(Duration.ofSeconds(5));
		assertThat(r).as("끝나지 않은 학생은 failed가 아니라 아직 켜는 중(unhealthy)")
				.isEqualTo(new ResumeResult(0, students, 0, 0));
		assertThat(courses.findStatusById(c.getId())).contains(CourseStatus.READY);
		assertThat(calledByDeadline).as("상한 때 큐에 남은 학생이 있었다").hasSizeLessThanOrEqualTo(
				LifecycleService.RESUME_POOL_SIZE);
		assertThat(allFinished).as("상한 뒤에도 큐에 있던 학생까지 끝까지 켠다").isTrue();
		assertThat(ensurer.ensureCalls).containsExactlyInAnyOrderElementsOf(ids);
		assertThat(interrupted).as("상한이 ContainerEnsurer·Docker 호출을 인터럽트하지 않는다").isEmpty();
		// 늦게 끝난 시도도 바뀐 호스트 포트를 저장한다.
		for (int i = 0; i < students; i++) {
			StudentSession s = sessions.findById(ids.get(i)).orElseThrow();
			assertThat(s.getHostPort()).isEqualTo(47100 + i);
			assertThat(s.isContainerRunning()).isTrue();
		}
	}

	@Test
	void resumeWithNoContainersJustReopens() {
		Course c = course("수업1", CourseStatus.ENDED, IMAGE_A);

		assertThat(service.resume(c.getId())).isEqualTo(new ResumeResult(0, 0, 0, 0));
		assertThat(courses.findStatusById(c.getId())).contains(CourseStatus.READY);
	}

	// ---- 수업 단위 잠금 ----

	@Test
	void endWaitsForResumeOnTheSameCourse() throws Exception {
		Course c = course("수업1", CourseStatus.ENDED, IMAGE_A);
		student(c, "가영", "c1", "v1");
		docker.addContainer("c1", "v1", false);
		CourseService courseService = courseService();
		CountDownLatch inEnsure = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ensurer.during = s -> {
			inEnsure.countDown();
			await(release, Duration.ofSeconds(10));
		};
		ensurer.after = s -> docker.callLog.add("ensure-done");
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<ResumeResult> resume = pool.submit(() -> service.resume(c.getId()));
			assertThat(inEnsure.await(10, TimeUnit.SECONDS)).isTrue();
			AtomicReference<Thread> endThread = new AtomicReference<>();
			Future<CourseService.EndResult> end = pool.submit(() -> {
				endThread.set(Thread.currentThread());
				return courseService.end(c.getId());
			});
			// 잠금이 없다면 end는 그 사이에 컨테이너를 멈추고 끝나 버린다.
			awaitBlockedOrDone(endThread, end);
			assertThat(docker.callLog).as("재개가 끝나기 전에는 멈추지 않는다").doesNotContain("stop:c1");
			release.countDown();
			assertThat(resume.get(10, TimeUnit.SECONDS).started()).isEqualTo(1);
			assertThat(end.get(10, TimeUnit.SECONDS).stopped()).isEqualTo(1);
		}
		finally {
			release.countDown();
			stop(pool);
		}
		assertThat(docker.callLog.indexOf("stop:c1")).isGreaterThan(docker.callLog.indexOf("ensure-done"));
		assertThat(courses.findStatusById(c.getId())).contains(CourseStatus.ENDED);
	}

	@Test
	void courseOperationsGet409WhileAnotherRunsButOtherCoursesDoNot() throws Exception {
		locks = new CourseLocks(Duration.ofMillis(100));
		service = serviceWith(Duration.ofSeconds(60));
		CourseService courseService = courseService();
		Course c = course("수업1", CourseStatus.ENDED, IMAGE_A);
		StudentSession s = student(c, "가영", "c1", "v1");
		docker.addContainer("c1", "v1", false);
		Course other = course("수업2", CourseStatus.READY, IMAGE_B);
		CountDownLatch inEnsure = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ensurer.during = x -> {
			inEnsure.countDown();
			await(release, Duration.ofSeconds(10));
		};
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<ResumeResult> resume = pool.submit(() -> service.resume(c.getId()));
			assertThat(inEnsure.await(10, TimeUnit.SECONDS)).isTrue();

			assertThatThrownBy(() -> courseService.end(c.getId())).isInstanceOf(CourseStateException.class)
					.hasMessageContaining("다른 작업이 진행 중입니다");
			assertThatThrownBy(() -> service.resume(c.getId())).isInstanceOf(CourseStateException.class)
					.hasMessageContaining("다른 작업이 진행 중입니다");
			assertThatThrownBy(() -> service.deleteCourse(c.getId(), "수업1"))
					.isInstanceOf(CourseStateException.class).hasMessageContaining("다른 작업이 진행 중입니다");
			assertThatThrownBy(() -> service.deleteStudent(c.getId(), s.getId()))
					.isInstanceOf(CourseStateException.class).hasMessageContaining("다른 작업이 진행 중입니다");
			// 잠금은 수업마다 따로다.
			assertThat(courseService.end(other.getId())).isEqualTo(new CourseService.EndResult(0, 0));

			release.countDown();
			assertThat(resume.get(10, TimeUnit.SECONDS).started()).isEqualTo(1);
		}
		finally {
			release.countDown();
			stop(pool);
		}
		assertThat(sessions.findById(s.getId())).as("겹친 학생 삭제는 아무것도 지우지 않았다").isPresent();
		assertThat(docker.callLog).doesNotContain("remove:c1", "removeVolume:v1", "stop:c1");
	}

	// ---- 수업 삭제 ----

	@Test
	void deleteRemovesOnlyRecorded() throws IOException {
		Course a = course("수업1", CourseStatus.ENDED, IMAGE_A);
		Course b = course("수업2", CourseStatus.ENDED, IMAGE_B);
		StudentSession a1 = student(a, "가영", "ca1", "swunion-test-s1-aaaa0001");
		StudentSession a2 = student(a, "나리", "ca2", "swunion-test-s2-aaaa0002");
		// 컨테이너를 만들기 전에 끊긴 행(볼륨 이름만 있다)과 아무 기록도 없는 행
		StudentSession a3 = student(a, "다은", null, "swunion-test-s3-aaaa0003");
		StudentSession a4 = student(a, "라온", " ", null);
		StudentSession b1 = student(b, "마루", "cb1", "swunion-test-s5-bbbb0005");
		for (StudentSession s : List.of(a1, a2, a3, a4, b1)) {
			childRows(s);
		}
		docker.addContainer("ca1", a1.getVolumeName(), true);
		docker.addContainer("ca2", a2.getVolumeName(), false);
		docker.volumes.add(a3.getVolumeName());
		docker.addContainer("cb1", b1.getVolumeName(), true);
		// DB에 기록되지 않은 자원. 접두사가 같아도 건드리면 안 된다.
		docker.addContainer("foreign", "swunion-test-s9-ffff0009", true);
		docker.volumes.add("swunion-test-s1-old00001");
		docker.images.addAll(List.of(IMAGE_A, IMAGE_B, "t/course-a:2", "b:1"));
		Path dirA = courseDir(a);
		Path dirB = courseDir(b);

		DeleteResult r = service.deleteCourse(a.getId(), "수업1");

		assertThat(r).isEqualTo(new DeleteResult(2, 3, List.of()));
		assertThat(docker.callLog).containsExactlyInAnyOrder("remove:ca1", "removeVolume:" + a1.getVolumeName(),
				"remove:ca2", "removeVolume:" + a2.getVolumeName(), "removeVolume:" + a3.getVolumeName(),
				"removeImage:" + IMAGE_A);
		assertThat(docker.callLog.indexOf("remove:ca1"))
				.as("볼륨은 컨테이너를 지운 뒤에").isLessThan(docker.callLog.indexOf("removeVolume:" + a1.getVolumeName()));
		assertThat(docker.containers).containsOnlyKeys("cb1", "foreign");
		assertThat(docker.volumes).containsExactlyInAnyOrder(b1.getVolumeName(), "swunion-test-s9-ffff0009",
				"swunion-test-s1-old00001");
		assertThat(docker.images).containsExactlyInAnyOrder(IMAGE_B, "t/course-a:2", "b:1");

		assertThat(courses.findById(a.getId())).isEmpty();
		assertThat(sessions.findByCourse_Id(a.getId())).isEmpty();
		for (StudentSession s : List.of(a1, a2, a3, a4)) {
			assertThat(childRowCount(s.getId())).isZero();
		}
		assertThat(courses.findById(b.getId())).isPresent();
		assertThat(sessions.findById(b1.getId())).isPresent();
		assertThat(childRowCount(b1.getId())).isEqualTo(4);

		assertThat(dirA).doesNotExist();
		assertThat(dirB.resolve("spec").resolve("pyproject.toml")).exists();
	}

	/** Review Focus 3: 누가 이미 docker rm -f, docker volume rm, docker rmi를 한 수업. */
	@Test
	void deleteToleratesMissing() throws IOException {
		Course c = course("수업1", CourseStatus.ENDED, IMAGE_A);
		StudentSession s1 = student(c, "가영", "gone1", "vol-gone1");
		StudentSession s2 = student(c, "나리", "gone2", "vol-gone2");
		childRows(s1);
		childRows(s2);
		Path dir = courseDir(c);

		DeleteResult r = service.deleteCourse(c.getId(), "수업1");

		assertThat(r.failures()).isEmpty();
		assertThat(r).isEqualTo(new DeleteResult(2, 2, List.of()));
		assertThat(docker.callLog).containsExactlyInAnyOrder("remove:gone1", "removeVolume:vol-gone1",
				"remove:gone2", "removeVolume:vol-gone2", "removeImage:" + IMAGE_A);
		assertThat(courses.count()).isZero();
		assertThat(sessions.count()).isZero();
		assertThat(actions.count()).isZero();
		assertThat(helps.count()).isZero();
		assertThat(checks.count()).isZero();
		assertThat(locals.count()).isZero();
		assertThat(dir).doesNotExist();
		assertThat(dataDir.resolve("courses")).as("상위 폴더는 남는다").isDirectory();
	}

	@Test
	void deleteReportsOtherFailuresAndStillDeletesRows() throws IOException {
		Course c = course("수업1", CourseStatus.ENDED, IMAGE_A);
		StudentSession s1 = student(c, "가영", "c1", "v1");
		StudentSession s2 = student(c, "나리", "c2", "v2");
		childRows(s1);
		childRows(s2);
		docker.addContainer("c1", "v1", true);
		docker.addContainer("c2", "v2", true);
		docker.images.add(IMAGE_A);
		docker.failOn("removeVolume:v1", new DockerOperationException("볼륨 삭제 실패: v1 /Users/someone/secret"));
		docker.failOn("remove:c2", new DockerOperationException("컨테이너 삭제 실패: c2 /var/run/docker.sock"));
		docker.failOn("removeImage:" + IMAGE_A, new IllegalStateException("connection refused /var/run/docker.sock"));
		Path dir = courseDir(c);

		DeleteResult r = service.deleteCourse(c.getId(), "수업1");

		// v2는 c2가 아직 붙어 있어 Docker가 거절한다(가짜도 그렇게 동작한다).
		assertThat(r.removedContainers()).isEqualTo(1);
		assertThat(r.removedVolumes()).isZero();
		assertThat(r.failures()).containsExactlyInAnyOrder("볼륨 v1", "컨테이너 c2", "볼륨 v2", "이미지 " + IMAGE_A);
		assertThat(String.join(" ", r.failures())).doesNotContain("/Users", "/var/run", "connection");
		assertThat(docker.callLog).containsExactlyInAnyOrder("remove:c1", "removeVolume:v1", "remove:c2",
				"removeVolume:v2", "removeImage:" + IMAGE_A);
		assertThat(courses.findById(c.getId())).isEmpty();
		assertThat(sessions.count()).isZero();
		assertThat(childRowCount(s1.getId()) + childRowCount(s2.getId())).isZero();
		assertThat(dir).doesNotExist();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "Py기초 ", " Py기초", "py기초", "Py", "Py기초1", "PY기초" })
	void deleteNeedsExactName(String confirmName) throws IOException {
		Course c = course("Py기초", CourseStatus.ENDED, IMAGE_A);
		StudentSession s = student(c, "가영", "c1", "v1");
		childRows(s);
		docker.addContainer("c1", "v1", false);
		Path dir = courseDir(c);

		assertThatThrownBy(() -> service.deleteCourse(c.getId(), confirmName))
				.isInstanceOf(ConfirmNameMismatchException.class).hasMessageContaining("수업 이름");

		assertUntouched(c, s, dir);
	}

	@ParameterizedTest
	@EnumSource(value = CourseStatus.class, names = { "READY", "LOCKING", "BUILDING" })
	void deleteOnlyEndedOrFailed(CourseStatus status) throws IOException {
		Course c = course("수업1", status, IMAGE_A);
		StudentSession s = student(c, "가영", "c1", "v1");
		childRows(s);
		docker.addContainer("c1", "v1", true);
		Path dir = courseDir(c);

		assertThatThrownBy(() -> service.deleteCourse(c.getId(), "수업1")).isInstanceOf(CourseStateException.class);

		assertUntouched(c, s, dir);
	}

	@Test
	void deleteFailedCourseRemovesItsImageAndFolder() throws IOException {
		Course c = course("실패한 수업", CourseStatus.FAILED, IMAGE_A);
		docker.images.add(IMAGE_A);
		Path dir = courseDir(c);

		assertThat(service.deleteCourse(c.getId(), "실패한 수업")).isEqualTo(new DeleteResult(0, 0, List.of()));

		assertThat(docker.callLog).containsExactly("removeImage:" + IMAGE_A);
		assertThat(courses.findById(c.getId())).isEmpty();
		assertThat(dir).doesNotExist();
	}

	@Test
	void deleteFailedCourseWithoutImageOrFolder() {
		Course c = course("실패한 수업", CourseStatus.FAILED, null);

		assertThat(service.deleteCourse(c.getId(), "실패한 수업")).isEqualTo(new DeleteResult(0, 0, List.of()));

		assertThat(docker.callLog).isEmpty();
		assertThat(courses.findById(c.getId())).isEmpty();
	}

	@Test
	void deleteBlockedByAgent() throws IOException {
		Course c = course("수업1", CourseStatus.ENDED, IMAGE_A);
		StudentSession s1 = student(c, "가영", "c1", "v1");
		StudentSession s2 = student(c, "나리", "c2", "v2");
		childRows(s1);
		docker.addContainer("c1", "v1", false);
		docker.addContainer("c2", "v2", false);
		agents.running.add(s2.getId());
		Path dir = courseDir(c);

		assertThatThrownBy(() -> service.deleteCourse(c.getId(), "수업1")).isInstanceOf(CourseStateException.class)
				.hasMessageContaining("에이전트");

		assertUntouched(c, s1, dir);
		assertThat(sessions.findById(s2.getId())).isPresent();
	}

	@Test
	void deleteUnknownCourseIsNotFound() {
		assertThatThrownBy(() -> service.deleteCourse(987654L, "수업1")).isInstanceOf(NotFoundException.class);
		assertThat(docker.callLog).isEmpty();
	}

	@Test
	void deleteRefusesCourseFolderThatPointsOutsideDataDir(@TempDir Path outside) throws IOException {
		Course c = course("수업1", CourseStatus.ENDED, null);
		Files.writeString(outside.resolve("keep.txt"), "지우면 안 되는 파일");
		Files.createDirectories(dataDir.resolve("courses"));
		Files.createSymbolicLink(dataDir.resolve("courses").resolve(String.valueOf(c.getId())), outside);

		DeleteResult r = service.deleteCourse(c.getId(), "수업1");

		assertThat(r.failures()).containsExactly("작업 폴더 courses/" + c.getId());
		assertThat(outside.resolve("keep.txt")).exists();
		assertThat(courses.findById(c.getId())).as("행은 그래도 지운다").isEmpty();
	}

	@Test
	void deleteRefusesCourseFolderThatPointsToAnotherCourse() throws IOException {
		Course c = course("수업1", CourseStatus.ENDED, null);
		Course other = course("수업2", CourseStatus.ENDED, null);
		Path otherDir = courseDir(other);
		Files.createSymbolicLink(dataDir.resolve("courses").resolve(String.valueOf(c.getId())), otherDir);

		DeleteResult r = service.deleteCourse(c.getId(), "수업1");

		assertThat(r.failures()).containsExactly("작업 폴더 courses/" + c.getId());
		assertThat(otherDir.resolve("Dockerfile")).exists();
		assertThat(courses.findById(other.getId())).isPresent();
	}

	@Test
	void deleteDoesNotFollowLinksInsideTheCourseFolder(@TempDir Path outside) throws IOException {
		Course c = course("수업1", CourseStatus.ENDED, null);
		Path dir = courseDir(c);
		Files.writeString(outside.resolve("keep.txt"), "지우면 안 되는 파일");
		Files.createSymbolicLink(dir.resolve("spec").resolve("link-to-outside"), outside);

		DeleteResult r = service.deleteCourse(c.getId(), "수업1");

		assertThat(r.failures()).isEmpty();
		assertThat(dir).doesNotExist();
		assertThat(outside.resolve("keep.txt")).exists();
	}

	@Test
	void rowDeletionIsOneTransaction() {
		Course c = course("수업1", CourseStatus.ENDED, null);
		StudentSession s = student(c, "가영", null, null);
		childRows(s);
		FAIL_SESSION_ROW_DELETE.set(true);

		assertThatThrownBy(() -> rows.deleteCourseRows(c.getId())).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> rows.deleteSessionRows(s.getId())).isInstanceOf(IllegalStateException.class);

		FAIL_SESSION_ROW_DELETE.set(false);
		assertThat(childRowCount(s.getId())).as("먼저 지운 하위 행이 되돌려졌다").isEqualTo(4);
		assertThat(sessions.findById(s.getId())).isPresent();
		assertThat(courses.findById(c.getId())).isPresent();
	}

	// ---- 학생 삭제 ----

	@Test
	void deleteStudentRemovesOne() {
		Course c = course("수업1", CourseStatus.READY, IMAGE_A);
		Course other = course("수업2", CourseStatus.READY, IMAGE_B);
		StudentSession s1 = student(c, "가영", "c1", "v1");
		StudentSession s2 = student(c, "나리", "c2", "v2");
		StudentSession o1 = student(other, "다은", "co1", "vo1");
		for (StudentSession s : List.of(s1, s2, o1)) {
			childRows(s);
		}
		docker.addContainer("c1", "v1", true);
		docker.addContainer("c2", "v2", true);
		docker.addContainer("co1", "vo1", true);
		docker.images.addAll(List.of(IMAGE_A, IMAGE_B));

		DeleteResult r = service.deleteStudent(c.getId(), s1.getId());

		assertThat(r).isEqualTo(new DeleteResult(1, 1, List.of()));
		assertThat(docker.callLog).containsExactly("remove:c1", "removeVolume:v1");
		assertThat(sessions.findById(s1.getId())).isEmpty();
		assertThat(childRowCount(s1.getId())).isZero();
		assertThat(sessions.findById(s2.getId())).isPresent();
		assertThat(childRowCount(s2.getId())).isEqualTo(4);
		assertThat(childRowCount(o1.getId())).isEqualTo(4);
		assertThat(courses.findStatusById(c.getId())).contains(CourseStatus.READY);
		assertThat(docker.containers).containsOnlyKeys("c2", "co1");
		assertThat(docker.volumes).containsExactlyInAnyOrder("v2", "vo1");
		assertThat(docker.images).containsExactlyInAnyOrder(IMAGE_A, IMAGE_B);
	}

	@Test
	void deleteStudentReportsDockerFailureAndStillDeletesRows() {
		Course c = course("수업1", CourseStatus.ENDED, IMAGE_A);
		StudentSession s = student(c, "가영", "c1", "v1");
		childRows(s);
		docker.addContainer("c1", "v1", false);
		docker.failOn("removeVolume:v1", new DockerOperationException("in use"));

		DeleteResult r = service.deleteStudent(c.getId(), s.getId());

		assertThat(r).isEqualTo(new DeleteResult(1, 0, List.of("볼륨 v1")));
		assertThat(sessions.findById(s.getId())).isEmpty();
		assertThat(childRowCount(s.getId())).isZero();
	}

	@Test
	void deleteStudentChecksMembershipAndAgent() {
		Course c = course("수업1", CourseStatus.READY, IMAGE_A);
		Course other = course("수업2", CourseStatus.READY, IMAGE_B);
		StudentSession s = student(c, "가영", "c1", "v1");
		StudentSession theirs = student(other, "나리", "c2", "v2");
		childRows(s);
		childRows(theirs);
		docker.addContainer("c1", "v1", true);
		docker.addContainer("c2", "v2", true);

		assertThatThrownBy(() -> service.deleteStudent(c.getId(), theirs.getId())).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.deleteStudent(c.getId(), 987654L)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.deleteStudent(987654L, s.getId())).isInstanceOf(NotFoundException.class);
		agents.running.add(s.getId());
		assertThatThrownBy(() -> service.deleteStudent(c.getId(), s.getId())).isInstanceOf(CourseStateException.class)
				.hasMessageContaining("에이전트");

		assertThat(docker.callLog).isEmpty();
		assertThat(childRowCount(s.getId())).isEqualTo(4);
		assertThat(childRowCount(theirs.getId())).isEqualTo(4);
	}

	// ---- 학생 재시작 ----

	@Test
	void restartStudentRestartsEvenARunningContainerAndWaitsForHealth() {
		Course c = course("수업1", CourseStatus.READY, IMAGE_A);
		StudentSession s = student(c, "가영", "c1", "v1");
		docker.addContainer("c1", "v1", true);
		docker.portOnStart.put("c1", 47100);

		assertThat(service.restartStudent(c.getId(), s.getId())).isTrue();

		assertThat(ensurer.restartCalls).containsExactly(s.getId());
		assertThat(ensurer.ensureCalls).as("돌고 있어도 다시 시작해야 한다").isEmpty();
		assertThat(docker.callLog).contains("restart:c1");
		assertThat(sessions.findById(s.getId()).orElseThrow().getHostPort()).isEqualTo(47100);
		assertThat(health.probedPorts).containsExactly(47100);
		assertThat(health.timeouts).containsExactly(Duration.ofSeconds(60));

		health.unhealthyPorts.add(47100);
		assertThat(service.restartStudent(c.getId(), s.getId())).isFalse();
	}

	@Test
	void restartStudentReportsFailureWithoutWaiting() {
		Course c = course("수업1", CourseStatus.READY, IMAGE_A);
		StudentSession s = student(c, "가영", "c1", "v1");
		ensurer.scripted.put(s.getId(), new EnsureResult(EnsureOutcome.FAILED, null));

		assertThat(service.restartStudent(c.getId(), s.getId())).isFalse();

		assertThat(ensurer.restartCalls).containsExactly(s.getId());
		assertThat(health.probedPorts).isEmpty();
	}

	@Test
	void restartStudentRules() {
		Course c = course("수업1", CourseStatus.READY, IMAGE_A);
		Course other = course("수업2", CourseStatus.READY, IMAGE_B);
		Course ended = course("수업3", CourseStatus.ENDED, IMAGE_A);
		StudentSession s = student(c, "가영", "c1", "v1");
		StudentSession theirs = student(other, "나리", "c2", "v2");
		StudentSession inEnded = student(ended, "다은", "c3", "v3");

		assertThatThrownBy(() -> service.restartStudent(c.getId(), theirs.getId())).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.restartStudent(c.getId(), 987654L)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.restartStudent(987654L, s.getId())).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.restartStudent(ended.getId(), inEnded.getId()))
				.isInstanceOf(CourseStateException.class);
		agents.running.add(s.getId());
		assertThatThrownBy(() -> service.restartStudent(c.getId(), s.getId())).isInstanceOf(CourseStateException.class)
				.hasMessageContaining("에이전트");

		assertThat(ensurer.restartCalls).isEmpty();
		assertThat(ensurer.ensureCalls).isEmpty();
		assertThat(docker.callLog).isEmpty();
		assertThat(health.probedPorts).isEmpty();
	}

	// ---- PIN 초기화 ----

	@Test
	void resetPinClearsOnlyThatStudent() {
		Course c = course("수업1", CourseStatus.READY, IMAGE_A);
		Course other = course("수업2", CourseStatus.READY, IMAGE_B);
		StudentSession s = sessions.save(new StudentSession(c, "가영", "가영", "tok-pin-1", "pw-1", "$2a$10$hash1"));
		StudentSession theirs = sessions.save(new StudentSession(other, "나리", "나리", "tok-pin-2", "pw-2", "$2a$10$hash2"));
		Instant later = Instant.parse("2030-01-01T00:00:00Z");
		for (int i = 0; i < 5; i++) {
			sessions.recordPinFailure(s.getId(), later);
		}

		service.resetPin(c.getId(), s.getId());

		StudentSession fresh = sessions.findById(s.getId()).orElseThrow();
		assertThat(fresh.getPinHash()).isNull();
		assertThat(fresh.getFailedPinAttempts()).isZero();
		assertThat(fresh.getPinLockedUntil()).isNull();
		assertThatThrownBy(() -> service.resetPin(c.getId(), theirs.getId())).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.resetPin(987654L, s.getId())).isInstanceOf(NotFoundException.class);
		assertThat(sessions.findById(theirs.getId()).orElseThrow().getPinHash()).isEqualTo("$2a$10$hash2");
		assertThat(docker.callLog).isEmpty();
	}

	// ---- 도우미 ----

	private static boolean await(CountDownLatch latch, Duration timeout) {
		try {
			return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** 스레드가 잠금 앞에서 기다리기 시작했거나 이미 끝났을 때까지(최대 5초) 기다린다. */
	private static void awaitBlockedOrDone(AtomicReference<Thread> thread, Future<?> future) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (System.nanoTime() < deadline && !future.isDone()) {
			Thread t = thread.get();
			if (t != null && (t.getState() == Thread.State.TIMED_WAITING || t.getState() == Thread.State.WAITING)) {
				return;
			}
			Thread.sleep(10);
		}
	}

	/** 실패한 테스트의 스레드가 정리(@AfterEach) 뒤에 행을 쓰면 다음 테스트가 깨지므로 끝날 때까지 기다린다. */
	private static void stop(ExecutorService pool) throws InterruptedException {
		pool.shutdownNow();
		assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).as("테스트 스레드가 끝났다").isTrue();
	}

	// ---- 가짜 ----

	/**
	 * Docker 상태를 흉내 낸다: 컨테이너(실행 여부, 붙은 볼륨), 볼륨, 이미지. 없는 자원을 지우면 실제 게이트웨이처럼
	 * 조용히 끝나고, 컨테이너가 붙은 볼륨은 지우지 못한다. 모든 호출을 "메서드:인자"로 남긴다.
	 */
	static class FakeDocker implements DockerGateway {
		final Map<String, Boolean> containers = new ConcurrentHashMap<>();
		final Map<String, String> mounts = new ConcurrentHashMap<>();
		final Set<String> volumes = ConcurrentHashMap.newKeySet();
		final Set<String> images = ConcurrentHashMap.newKeySet();
		final Map<String, Integer> ports = new ConcurrentHashMap<>();
		/** 켜거나 다시 시작할 때 줄 호스트 포트. 없으면 차례로 새 포트를 준다. */
		final Map<String, Integer> portOnStart = new ConcurrentHashMap<>();
		final Map<String, RuntimeException> failures = new ConcurrentHashMap<>();
		final List<String> callLog = new CopyOnWriteArrayList<>();
		final AtomicInteger nextPort = new AtomicInteger(46000);

		void addContainer(String id, String volume, boolean running) {
			containers.put(id, running);
			mounts.put(id, volume);
			volumes.add(volume);
		}

		void failOn(String call, RuntimeException e) {
			failures.put(call, e);
		}

		private void call(String call) {
			callLog.add(call);
			RuntimeException e = failures.get(call);
			if (e != null) {
				throw e;
			}
		}

		private void assignPort(String id) {
			ports.put(id, portOnStart.getOrDefault(id, nextPort.getAndIncrement()));
		}

		public boolean isRunning(String id) {
			call("isRunning:" + id);
			return containers.getOrDefault(id, false);
		}

		public boolean containerExists(String id) {
			call("containerExists:" + id);
			return containers.containsKey(id);
		}

		public boolean volumeExists(String v) {
			call("volumeExists:" + v);
			return volumes.contains(v);
		}

		public void restart(String id) {
			call("restart:" + id);
			if (!containers.containsKey(id)) {
				throw new DockerOperationException("no such container: " + id);
			}
			containers.put(id, true);
			assignPort(id);
		}

		public String createAndStart(ContainerSpec spec) {
			call("createAndStart:" + spec.volumeName());
			String id = "new-" + spec.volumeName();
			addContainer(id, spec.volumeName(), true);
			assignPort(id);
			return id;
		}

		public int hostPort(String id, int containerPort) {
			call("hostPort:" + id);
			Integer port = ports.get(id);
			if (port == null) {
				throw new DockerOperationException("no port: " + id);
			}
			return port;
		}

		public void stop(String id) {
			call("stop:" + id);
			containers.computeIfPresent(id, (k, v) -> false);
		}

		public void remove(String id) {
			call("remove:" + id);
			containers.remove(id);
			mounts.remove(id);
		}

		public void removeVolume(String v) {
			call("removeVolume:" + v);
			if (mounts.containsValue(v)) {
				throw new DockerOperationException("volume is in use: " + v);
			}
			volumes.remove(v);
		}

		public void removeImage(String image) {
			call("removeImage:" + image);
			images.remove(image);
		}

		public boolean imageExists(String image) {
			call("imageExists:" + image);
			return images.contains(image);
		}

		public String buildImage(Path d, String t, Consumer<String> l) {
			call("buildImage:" + t);
			throw new UnsupportedOperationException();
		}

		public ExecResult runOnce(String i, List<String> c, Duration t) {
			call("runOnce:" + i);
			throw new UnsupportedOperationException();
		}

		public ExecResult exec(String id, List<String> c, Duration t) {
			call("exec:" + id);
			throw new UnsupportedOperationException();
		}

		public ExecResult exec(String id, List<String> c, String w, Duration t) {
			call("exec:" + id);
			throw new UnsupportedOperationException();
		}

		public InputStream copyArchive(String id, String p) {
			call("copyArchive:" + id);
			throw new UnsupportedOperationException();
		}
	}

	/** 포트별로 응답 여부를 정하고, 어떤 포트를 얼마 동안 기다리라고 받았는지 남긴다. */
	static class FakeHealth implements HealthWaiter {
		final List<Integer> probedPorts = new CopyOnWriteArrayList<>();
		final List<Duration> timeouts = new CopyOnWriteArrayList<>();
		final Set<Integer> unhealthyPorts = ConcurrentHashMap.newKeySet();
		volatile IntConsumer during = port -> {
		};

		@Override
		public boolean awaitHealthy(int hostPort, Duration timeout) {
			probedPorts.add(hostPort);
			timeouts.add(timeout);
			during.accept(hostPort);
			return !unhealthyPorts.contains(hostPort);
		}
	}

	/** 실제 ContainerEnsurer(FakeDocker 위)로 돌되, 세션별로 결과를 정해 둘 수 있고 호출을 남긴다. */
	static class ScriptedEnsurer extends ContainerEnsurer {
		final List<Long> ensureCalls = new CopyOnWriteArrayList<>();
		final List<Long> restartCalls = new CopyOnWriteArrayList<>();
		final Map<Long, EnsureResult> scripted = new ConcurrentHashMap<>();
		volatile Consumer<StudentSession> during = s -> {
		};
		volatile Consumer<StudentSession> after = s -> {
		};

		ScriptedEnsurer(SessionRepository sessions, DockerGateway docker, ContainerSpecs specs, Clock clock) {
			super(sessions, docker, specs, clock);
		}

		@Override
		public EnsureResult ensureRunning(StudentSession s) {
			ensureCalls.add(s.getId());
			during.accept(s);
			EnsureResult r = scripted.containsKey(s.getId()) ? scripted.get(s.getId()) : super.ensureRunning(s);
			after.accept(s);
			return r;
		}

		@Override
		public EnsureResult restart(StudentSession s) {
			restartCalls.add(s.getId());
			during.accept(s);
			EnsureResult r = scripted.containsKey(s.getId()) ? scripted.get(s.getId()) : super.restart(s);
			after.accept(s);
			return r;
		}
	}

	/** 에이전트가 실행 중인 세션을 정해 둔다. 실제 에이전트는 돌리지 않는다. */
	static class FakeAgents extends AgentTrigger {
		final Set<Long> running = ConcurrentHashMap.newKeySet();

		FakeAgents() {
			super(null, null);
		}

		@Override
		public boolean isRunning(long sessionId) {
			return running.contains(sessionId);
		}
	}
}
