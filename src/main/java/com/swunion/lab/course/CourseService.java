package com.swunion.lab.course;

import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.session.SessionRepository;
import com.swunion.lab.session.StudentSession;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CourseService {

	private static final Logger log = LoggerFactory.getLogger(CourseService.class);

	/** 수업 종료 결과. failed는 멈추지 못하고 아직 실행 중인 컨테이너 수다. */
	public record EndResult(int stopped, int failed) {
	}

	private final CourseRepository repo;
	private final CourseProvisioner provisioner;
	private final TaskExecutor executor;
	private final JsonMapper json;
	private final SessionRepository sessions;
	private final DockerGateway docker;

	public CourseService(CourseRepository repo, CourseProvisioner provisioner,
			@Qualifier("courseExecutor") TaskExecutor executor, JsonMapper json, SessionRepository sessions,
			DockerGateway docker) {
		this.repo = repo;
		this.provisioner = provisioner;
		this.executor = executor;
		this.json = json;
		this.sessions = sessions;
		this.docker = docker;
	}

	/** 검증은 동기로 하고(실패하면 InvalidSpecException), 락과 빌드는 백그라운드로 넘긴다. */
	public Course create(String name, String pythonVersion, String packagesText) {
		String cleanName = name == null ? "" : name.trim();
		if (cleanName.isEmpty() || cleanName.length() > 100) {
			throw new InvalidSpecException("수업 이름은 1자 이상 100자 이하로 입력해 주세요.");
		}
		String python = SpecValidator.parsePythonVersion(pythonVersion);
		List<PackageSpec> packages = SpecValidator.parsePackages(packagesText);
		String normalized = packages.stream().map(PackageSpec::toLine).collect(Collectors.joining("\n"));
		Course course = repo.save(new Course(cleanName, python, normalized));
		long id = course.getId();
		try {
			executor.execute(() -> provisioner.provision(id));
		}
		catch (TaskRejectedException e) {
			// 큐가 가득 차면 저장된 행이 LOCKING에 갇히므로 바로 FAILED로 돌린다.
			course.setStatus(CourseStatus.FAILED);
			course.setErrorSummary("서버가 바빠 발급을 시작하지 못했습니다. 다시 시도해 주세요");
			course = repo.save(course);
		}
		return course;
	}

	public List<Course> list() {
		return repo.findAllByOrderByIdDesc();
	}

	public Course get(long id) {
		return repo.findById(id).orElse(null);
	}

	public Map<String, String> baseline(Course course) {
		if (course.getBaselineJson() == null) {
			return Map.of();
		}
		return json.readValue(course.getBaselineJson(), new TypeReference<Map<String, String>>() {
		});
	}

	public Path specDir(long courseId) {
		return provisioner.specDir(courseId);
	}

	/**
	 * 수업의 모든 컨테이너를 멈추고 수업을 ENDED로 바꾼다. 볼륨은 지우지 않는다(학생 코드가 남는다).
	 * 상태를 먼저 바꾸는 이유: 멈추는 동안 들어오는 입장을 막으려는 것이다.
	 * READY일 때만 바꾸는 조건부 갱신이라 아직 발급 중인 수업을 덮어쓰지 않는다.
	 * 이미 ENDED면 멈추기만 다시 한다. 첫 종료가 못 멈췄거나 그 뒤 되살아난 컨테이너를 강사가 다시 멈출 수 있어야 한다.
	 * 트랜잭션을 잡지 않는다: docker stop이 컨테이너마다 몇 초 걸린다.
	 *
	 * @return 수업이 없으면 null
	 * @throws CourseStateException READY도 ENDED도 아닌 수업(발급 중이거나 실패)
	 */
	public EndResult end(long id) {
		Course course = repo.findById(id).orElse(null);
		if (course == null) {
			return null;
		}
		if (repo.updateStatusIf(id, CourseStatus.READY, CourseStatus.ENDED) == 0) {
			// 위 갱신이 영속성 컨텍스트를 비우므로 다시 읽으면 그 사이 바뀐 최신 상태가 나온다.
			CourseStatus now = repo.findById(id).map(Course::getStatus).orElse(course.getStatus());
			if (now != CourseStatus.ENDED) {
				throw new CourseStateException("준비가 끝난 수업만 종료할 수 있습니다. 현재 상태: " + now);
			}
		}
		int stopped = 0;
		int failed = 0;
		for (StudentSession s : sessions.findByCourse_IdAndContainerIdIsNotNull(id)) {
			// 한 컨테이너의 실패가 나머지를 막지 않는다.
			if (stopContainer(s)) {
				sessions.markStopped(s.getId());
				stopped++;
			}
			else {
				failed++;
			}
		}
		return new EndResult(stopped, failed);
	}

	/**
	 * stop은 이미 멈췄거나 없는 컨테이너에도 던진다. 던져도 컨테이너가 실행 중이 아니면 목적을 이룬 것으로 본다.
	 * 실행 여부를 알 수 없으면 멈췄다고 주장하지 않는다.
	 */
	private boolean stopContainer(StudentSession s) {
		String containerId = s.getContainerId();
		try {
			docker.stop(containerId);
			return true;
		}
		catch (RuntimeException e) {
			log.warn("세션 {} 컨테이너 중단 실패: {}", s.getId(), e.getMessage());
		}
		try {
			return !docker.isRunning(containerId);
		}
		catch (RuntimeException e) {
			log.warn("세션 {} 컨테이너 상태 확인 실패: {}", s.getId(), e.getMessage());
			return false;
		}
	}
}
