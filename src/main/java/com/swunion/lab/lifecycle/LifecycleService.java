package com.swunion.lab.lifecycle;

import com.swunion.lab.agent.AgentTrigger;
import com.swunion.lab.config.LabProperties;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseLocks;
import com.swunion.lab.course.CourseProvisioner;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.course.CourseStateException;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.session.ContainerEnsurer;
import com.swunion.lab.session.ContainerEnsurer.EnsureOutcome;
import com.swunion.lab.session.ContainerEnsurer.EnsureResult;
import com.swunion.lab.session.HealthWaiter;
import com.swunion.lab.session.SessionRepository;
import com.swunion.lab.session.SessionService;
import com.swunion.lab.session.StudentSession;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 강사의 수업·학생 수명 관리: 수업 재개와 삭제, 학생 한 명의 재시작·삭제·PIN 초기화.
 * 지우는 Docker 자원은 DB에 기록된 컨테이너 id, 볼륨 이름, 이미지 이름뿐이다. 이름 접두사로 훑어서 지우지 않는다.
 */
@Service
public class LifecycleService {

	private static final Logger log = LoggerFactory.getLogger(LifecycleService.class);
	/** 재개가 컨테이너를 켜고 IDE 응답을 기다리는 전체 상한. */
	static final Duration RESUME_DEADLINE = Duration.ofSeconds(60);
	/** 재개가 동시에 켜는 학생 수. 수업 전체를 한꺼번에 켜면 Docker 데몬과 IDE 기동이 몰린다. */
	static final int RESUME_POOL_SIZE = 8;

	public record ResumeResult(int started, int unhealthy, int recreatedEmpty, int failed) {
	}

	/** failures는 지우지 못한 자원 이름이다. 강사가 직접 치울 수 있게 이름만 담고 오류 원문은 로그에만 남긴다. */
	public record DeleteResult(int removedContainers, int removedVolumes, List<String> failures) {
	}

	/** 없는 수업, 또는 그 수업 소속이 아닌 학생. 컨트롤러가 404로 바꾼다. */
	public static class NotFoundException extends RuntimeException {

		public NotFoundException(String messageKo) {
			super(messageKo);
		}
	}

	/** 수업 삭제 확인 이름이 수업 이름과 정확히 같지 않다. 컨트롤러가 400으로 바꾼다. */
	public static class ConfirmNameMismatchException extends RuntimeException {

		public ConfirmNameMismatchException(String messageKo) {
			super(messageKo);
		}
	}

	private enum Tally {
		STARTED, UNHEALTHY, RECREATED_EMPTY, FAILED
	}

	private final CourseRepository courses;
	private final SessionRepository sessions;
	private final ContainerEnsurer ensurer;
	private final HealthWaiter health;
	private final AgentTrigger agents;
	private final DockerGateway docker;
	private final SessionService sessionService;
	private final LifecycleRows rows;
	private final CourseLocks locks;
	private final LabProperties props;
	private final Duration resumeDeadline;

	@Autowired
	public LifecycleService(CourseRepository courses, SessionRepository sessions, ContainerEnsurer ensurer,
			HealthWaiter health, AgentTrigger agents, DockerGateway docker, SessionService sessionService,
			LifecycleRows rows, CourseLocks locks, LabProperties props) {
		this(courses, sessions, ensurer, health, agents, docker, sessionService, rows, locks, props, RESUME_DEADLINE);
	}

	/** 테스트가 재개 상한을 줄인다. */
	LifecycleService(CourseRepository courses, SessionRepository sessions, ContainerEnsurer ensurer,
			HealthWaiter health, AgentTrigger agents, DockerGateway docker, SessionService sessionService,
			LifecycleRows rows, CourseLocks locks, LabProperties props, Duration resumeDeadline) {
		this.courses = courses;
		this.sessions = sessions;
		this.ensurer = ensurer;
		this.health = health;
		this.agents = agents;
		this.docker = docker;
		this.sessionService = sessionService;
		this.rows = rows;
		this.locks = locks;
		this.props = props;
		this.resumeDeadline = resumeDeadline;
	}

	// ---- 수업 재개 ----

	/**
	 * 끝난 수업을 다시 연다. 수업이 아직 ENDED인 동안 컨테이너 기록이 있는 학생마다 컨테이너를 돌게 하고 IDE 응답을
	 * 기다린 뒤(전체 상한 resumeDeadline), 마지막에 READY로 바꾼다. 점검 스케줄러는 READY 수업만 보므로, 켜지는 중인
	 * 컨테이너를 "IDE 중단"으로 보고 에이전트를 부르지 않는다. 한 학생이 실패해도 나머지는 계속하고 수업은 READY가 된다.
	 * 상한까지 끝나지 않은 학생은 unhealthy(아직 켜는 중)로 세고, 그 시도는 응답 뒤에도 끊지 않고 끝까지 간다.
	 *
	 * @throws NotFoundException 없는 수업
	 * @throws CourseStateException ENDED가 아닌 수업, 또는 같은 수업의 다른 작업이 진행 중
	 */
	public ResumeResult resume(long courseId) {
		requireCourse(courseId);
		return locks.run(courseId, () -> resumeLocked(courseId));
	}

	private ResumeResult resumeLocked(long courseId) {
		CourseStatus status = courses.findStatusById(courseId).orElseThrow(LifecycleService::courseNotFound);
		if (status != CourseStatus.ENDED) {
			throw new CourseStateException("종료된 수업만 재개할 수 있습니다. 현재 상태: " + status);
		}
		// 컨테이너 기록도 볼륨 이름도 없는 행은 입장 도중 끊긴 것이라 켤 것이 없다.
		List<StudentSession> targets = sessions.findByCourse_Id(courseId).stream()
				.filter(s -> hasText(s.getContainerId()) || hasText(s.getVolumeName()))
				.toList();
		ResumeResult result = startAll(targets);
		if (courses.updateStatusIf(courseId, CourseStatus.ENDED, CourseStatus.READY) == 0) {
			// 종료·삭제는 같은 잠금을 쓰므로 여기 올 일은 없다. 그래도 READY가 아닌데 재개했다고 답하지 않는다.
			throw new CourseStateException("재개하는 사이 수업 상태가 바뀌었습니다. 수업 목록을 다시 확인해 주세요.");
		}
		log.info("수업 {} 재개: 시작 {}, IDE 무응답 {}, 빈 작업 공간 {}, 실패 {}", courseId, result.started(),
				result.unhealthy(), result.recreatedEmpty(), result.failed());
		return result;
	}

	private ResumeResult startAll(List<StudentSession> targets) {
		if (targets.isEmpty()) {
			return new ResumeResult(0, 0, 0, 0);
		}
		long deadline = System.nanoTime() + resumeDeadline.toNanos();
		ExecutorService pool = Executors.newFixedThreadPool(Math.min(RESUME_POOL_SIZE, targets.size()),
				resumeThreads());
		try {
			List<Attempt> attempts = new ArrayList<>();
			for (StudentSession s : targets) {
				Attempt a = new Attempt(s, deadline);
				a.future = pool.submit(a::run);
				attempts.add(a);
			}
			int[] counts = new int[Tally.values().length];
			for (Attempt a : attempts) {
				counts[a.await().ordinal()]++;
			}
			return new ResumeResult(counts[Tally.STARTED.ordinal()], counts[Tally.UNHEALTHY.ordinal()],
					counts[Tally.RECREATED_EMPTY.ordinal()], counts[Tally.FAILED.ordinal()]);
		}
		finally {
			// 상한은 응답만 끊는다. shutdownNow처럼 인터럽트하면 httpclient5의 Docker 호출이 중간에 실패해, 다시 만들던
			// 컨테이너가 기록되지 않은 채 학생 볼륨 이름을 쥐고 남는다. 그러면 이후 재입장·재시작이 모두 이름 충돌로
			// 실패한다. 돌던 시도와 큐에 남은 시도는 뒤에서 끝까지 가고, 끝나면 ContainerEnsurer가 포트를 저장한다.
			// 수업은 곧 READY가 되므로 늦게 켜진 컨테이너도 그대로 쓰면 된다. 일이 다 끝나면 풀 스레드도 끝난다.
			pool.shutdown();
		}
	}

	/** 학생 한 명의 재개. 상한에 걸리면 그때까지 확인한 결과로 센다. 시도 자체는 상한 뒤에도 계속된다. */
	private final class Attempt {

		private final StudentSession session;
		private final long deadline;
		private volatile EnsureOutcome outcome;
		private Future<Tally> future;

		Attempt(StudentSession session, long deadline) {
			this.session = session;
			this.deadline = deadline;
		}

		Tally run() {
			EnsureResult r = ensurer.ensureRunning(session);
			outcome = r.outcome();
			if (r.outcome() == EnsureOutcome.FAILED) {
				return Tally.FAILED;
			}
			boolean healthy = awaitHealthy(r.hostPort());
			// 작업 공간을 잃은 학생은 IDE가 떠도 강사가 따로 알아야 하므로 응답 여부와 상관없이 따로 센다.
			if (r.outcome() == EnsureOutcome.RECREATED_EMPTY) {
				return Tally.RECREATED_EMPTY;
			}
			return healthy ? Tally.STARTED : Tally.UNHEALTHY;
		}

		private boolean awaitHealthy(Integer hostPort) {
			long left = deadline - System.nanoTime();
			if (hostPort == null || left <= 0) {
				return false;
			}
			return health.awaitHealthy(hostPort, Duration.ofNanos(left));
		}

		Tally await() {
			try {
				return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
			}
			catch (TimeoutException e) {
				// 시도는 취소하지 않는다. 큐에 있던 것을 cancel하면 아예 돌지 않고, 도는 것을 인터럽트하면 Docker 호출이
				// 중간에 끊긴다(startAll의 finally 참고).
				log.warn("세션 {}: 재개 상한({}ms) 안에 끝나지 않아 응답 뒤에도 계속 켭니다.", session.getId(),
						resumeDeadline.toMillis());
				return unfinished();
			}
			catch (ExecutionException e) {
				log.error("세션 {} 재개 중 오류", session.getId(), e.getCause());
				// 컨테이너 단계에서 예외가 났으면 켜지 못한 것이다.
				return fromProgress(Tally.FAILED);
			}
			catch (InterruptedException e) {
				// 요청 스레드가 끊겼다. 기다리기만 그만두고 시도는 그대로 둔다.
				Thread.currentThread().interrupt();
				return unfinished();
			}
		}

		/** 상한까지 끝나지 않았다. 시도는 뒤에서 계속되므로 컨테이너 단계 실패가 확인된 경우만 failed다. */
		private Tally unfinished() {
			return fromProgress(Tally.UNHEALTHY);
		}

		/**
		 * 끝까지 보지 못했다. 컨테이너가 돌기 시작했으면 IDE 응답만 못 본 것이다.
		 * @param beforeContainer 컨테이너 단계가 아직 끝나지 않았을 때의 집계
		 */
		private Tally fromProgress(Tally beforeContainer) {
			EnsureOutcome o = outcome;
			if (o == null) {
				return beforeContainer;
			}
			if (o == EnsureOutcome.FAILED) {
				return Tally.FAILED;
			}
			return o == EnsureOutcome.RECREATED_EMPTY ? Tally.RECREATED_EMPTY : Tally.UNHEALTHY;
		}
	}

	private static ThreadFactory resumeThreads() {
		AtomicInteger n = new AtomicInteger();
		return r -> {
			Thread t = new Thread(r, "course-resume-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		};
	}

	// ---- 수업 삭제 ----

	/**
	 * 끝난(또는 발급에 실패한) 수업을 통째로 지운다: 학생마다 기록된 컨테이너와 볼륨, 수업 이미지, 작업 폴더, DB 행.
	 * Docker 자원 하나를 못 지워도 나머지를 계속하고 DB 행도 지운다. 남은 자원 이름은 결과의 failures로 돌려준다.
	 *
	 * @throws NotFoundException 없는 수업
	 * @throws ConfirmNameMismatchException confirmName이 수업 이름과 정확히 같지 않다
	 * @throws CourseStateException ENDED·FAILED가 아니거나, 에이전트가 실행 중인 학생이 있거나, 다른 작업이 진행 중
	 */
	public DeleteResult deleteCourse(long courseId, String confirmName) {
		requireCourse(courseId);
		return locks.run(courseId, () -> deleteCourseLocked(courseId, confirmName));
	}

	private DeleteResult deleteCourseLocked(long courseId, String confirmName) {
		Course course = courses.findById(courseId).orElseThrow(LifecycleService::courseNotFound);
		// 다른 수업을 지우는 실수를 막으려는 확인이라 앞뒤 공백이나 대소문자도 맞춰 주지 않는다.
		if (confirmName == null || !confirmName.equals(course.getName())) {
			throw new ConfirmNameMismatchException("수업 이름이 정확히 일치하지 않습니다.");
		}
		CourseStatus status = course.getStatus();
		if (status != CourseStatus.ENDED && status != CourseStatus.FAILED) {
			throw new CourseStateException("종료됐거나 발급에 실패한 수업만 지울 수 있습니다. 현재 상태: " + status);
		}
		List<StudentSession> students = sessions.findByCourse_Id(courseId);
		// ENDED·FAILED 수업에서는 점검도 도움 요청도 에이전트를 새로 부르지 않으므로, 지금 도는 것만 보면 된다.
		if (students.stream().anyMatch(s -> agents.isRunning(s.getId()))) {
			throw new CourseStateException("에이전트가 처리 중인 학생이 있습니다. 끝난 뒤 다시 시도해 주세요.");
		}
		Removal removal = new Removal();
		for (StudentSession s : students) {
			removal.sessionResources(s);
		}
		removal.image(course.getImageName());
		removal.courseDir(courseId);
		rows.deleteCourseRows(courseId);
		DeleteResult result = removal.result();
		log.info("수업 {} 삭제: 학생 {}명, 컨테이너 {}개, 볼륨 {}개, 남은 자원 {}개", courseId, students.size(),
				result.removedContainers(), result.removedVolumes(), result.failures().size());
		return result;
	}

	// ---- 학생 한 명 ----

	/**
	 * 학생 컨테이너를 다시 시작하고 IDE 응답을 기다린다(최대 60초). 돌고 있어도 다시 시작한다: 강사가 누르는 것은 대개
	 * 컨테이너는 돌지만 IDE가 응답하지 않을 때다. 시작과 대기는 에이전트의 restart_ide와 같은
	 * SessionService.restartContainer(ContainerEnsurer.restart + IDE 대기)를 쓴다.
	 *
	 * @return IDE가 응답하면 true, 시작하지 못했거나 응답이 없으면 false
	 * @throws NotFoundException 없는 수업, 또는 그 수업 소속이 아닌 학생
	 * @throws CourseStateException READY가 아닌 수업, 또는 그 학생에게 에이전트가 실행 중
	 */
	public boolean restartStudent(long courseId, long sessionId) {
		member(courseId, sessionId);
		CourseStatus status = courses.findStatusById(courseId).orElseThrow(LifecycleService::courseNotFound);
		if (status != CourseStatus.READY) {
			throw new CourseStateException("진행 중인 수업에서만 재시작할 수 있습니다. 현재 상태: " + status);
		}
		requireNoAgent(sessionId);
		return sessionService.restartContainer(sessionId);
	}

	/**
	 * 학생 한 명의 컨테이너, 볼륨(학생 코드), DB 행을 지운다. 같은 수업의 다른 학생은 건드리지 않는다.
	 * 이 기능 전에 생긴 같은 이름의 중복 행 중 가장 최근 것을 지우면, 그다음 행(PIN이 없을 수 있다)이 그 이름의
	 * 되찾기 대상이 된다.
	 *
	 * @throws NotFoundException 없는 수업, 또는 그 수업 소속이 아닌 학생
	 * @throws CourseStateException 그 학생에게 에이전트가 실행 중, 또는 같은 수업의 다른 작업이 진행 중
	 */
	public DeleteResult deleteStudent(long courseId, long sessionId) {
		requireCourse(courseId);
		return locks.run(courseId, () -> {
			StudentSession s = member(courseId, sessionId);
			requireNoAgent(sessionId);
			Removal removal = new Removal();
			removal.sessionResources(s);
			rows.deleteSessionRows(sessionId);
			DeleteResult result = removal.result();
			log.info("수업 {} 학생 {} 삭제: 컨테이너 {}개, 볼륨 {}개, 남은 자원 {}개", courseId, sessionId,
					result.removedContainers(), result.removedVolumes(), result.failures().size());
			return result;
		});
	}

	/**
	 * PIN 해시, 실패 횟수, 잠금을 비운다. 다음에 그 이름으로 들어오는 사람이 새 PIN을 정한다.
	 *
	 * @throws NotFoundException 없는 수업, 또는 그 수업 소속이 아닌 학생
	 */
	public void resetPin(long courseId, long sessionId) {
		member(courseId, sessionId);
		sessionService.resetPin(sessionId);
	}

	// ---- 공통 ----

	// 엔티티를 읽지 않는 확인이다. 요청 하나가 영속성 컨텍스트를 끝까지 쥐는 설정(open-in-view)에서도, 잠금 안에서
	// 처음 읽는 수업·세션 엔티티가 이전에 캐시된 낡은 값이 되지 않는다.
	private void requireCourse(long courseId) {
		if (!courses.existsById(courseId)) {
			throw courseNotFound();
		}
	}

	private StudentSession member(long courseId, long sessionId) {
		requireCourse(courseId);
		StudentSession s = sessions.findById(sessionId).orElse(null);
		if (s == null || s.getCourse() == null || !Objects.equals(s.getCourse().getId(), courseId)) {
			throw new NotFoundException("이 수업의 학생을 찾을 수 없습니다.");
		}
		return s;
	}

	private void requireNoAgent(long sessionId) {
		if (agents.isRunning(sessionId)) {
			throw new CourseStateException("에이전트가 이 학생의 환경을 처리하는 중입니다. 끝난 뒤 다시 시도해 주세요.");
		}
	}

	private static NotFoundException courseNotFound() {
		return new NotFoundException("수업을 찾을 수 없습니다.");
	}

	private static boolean hasText(String v) {
		return v != null && !v.isBlank();
	}

	/** 지운 자원과 남은 자원을 모은다. 하나를 못 지워도 나머지를 계속 지운다. */
	private final class Removal {

		private int containers;
		private int volumes;
		private final List<String> failures = new ArrayList<>();

		/** 세션에 기록된 컨테이너와 볼륨만 지운다. 컨테이너가 붙은 볼륨은 지워지지 않으므로 컨테이너가 먼저다. */
		void sessionResources(StudentSession s) {
			String containerId = s.getContainerId();
			if (hasText(containerId) && attempt(() -> docker.remove(containerId), "컨테이너 " + containerId)) {
				containers++;
			}
			String volume = s.getVolumeName();
			if (hasText(volume) && attempt(() -> docker.removeVolume(volume), "볼륨 " + volume)) {
				volumes++;
			}
		}

		void image(String image) {
			if (hasText(image)) {
				attempt(() -> docker.removeImage(image), "이미지 " + image);
			}
		}

		/**
		 * {dataDir}/courses/{id}를 지운다. 실제 경로가 정확히 그 자리일 때만 지운다: 그 폴더가 다른 곳(다른 수업 폴더
		 * 포함)을 가리키는 링크면 거절한다. 안에 든 링크는 따라가지 않고 링크만 지운다.
		 */
		void courseDir(long courseId) {
			Path dir = CourseProvisioner.courseDir(props.dataDir(), courseId);
			String label = "작업 폴더 courses/" + courseId;
			if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
				return;
			}
			try {
				Path expected = CourseProvisioner.coursesRoot(props.dataDir()).toRealPath()
						.resolve(String.valueOf(courseId));
				if (Files.isSymbolicLink(dir) || !dir.toRealPath().equals(expected)) {
					log.error("수업 {}: 작업 폴더가 courses 폴더의 자기 자리 밖을 가리켜 지우지 않습니다.", courseId);
					failures.add(label);
					return;
				}
				deleteTree(expected);
			}
			catch (IOException | RuntimeException e) {
				log.warn("수업 {} 작업 폴더 삭제 실패: {}", courseId, e.toString());
				failures.add(label);
			}
		}

		private boolean attempt(Runnable op, String resource) {
			try {
				op.run();
				return true;
			}
			catch (RuntimeException e) {
				// 게이트웨이는 이미 없는 자원에는 던지지 않는다. 여기 오는 것은 남아 있을 수 있는 자원이다.
				// 오류 원문에는 Docker 내부 정보나 호스트 경로가 섞일 수 있어 응답에는 자원 이름만 싣는다.
				log.warn("{} 삭제 실패: {}", resource, e.getMessage());
				failures.add(resource);
				return false;
			}
		}

		DeleteResult result() {
			return new DeleteResult(containers, volumes, List.copyOf(failures));
		}
	}

	// FOLLOW_LINKS 없이 걸으므로 안에 든 심볼릭 링크는 visitFile로 오고, Files.delete는 링크 자체만 지운다.
	private static void deleteTree(Path root) throws IOException {
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				if (exc != null) {
					throw exc;
				}
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
