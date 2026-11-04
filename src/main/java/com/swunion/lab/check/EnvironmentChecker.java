package com.swunion.lab.check;

import com.swunion.lab.course.CourseService;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.docker.ExecResult;
import com.swunion.lab.session.SessionRepository;
import com.swunion.lab.session.StudentSession;
import com.swunion.lab.session.EnvStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class EnvironmentChecker {

	private static final Logger log = LoggerFactory.getLogger(EnvironmentChecker.class);
	static final Duration EXEC_TIMEOUT = Duration.ofSeconds(20);
	// 8080으로 ESTABLISHED인 연결은 전부 "IDE 접속"으로 센다. 서버가 거는 프로브는 짧게 열고 닫아야 한다.
	static final int IDE_PORT = 8080;

	private final DockerGateway docker;
	private final CourseService courses;
	private final SessionRepository sessions;
	private final EnvironmentCheckRepository checks;
	private final JsonMapper json;

	public EnvironmentChecker(DockerGateway docker, CourseService courses, SessionRepository sessions,
			EnvironmentCheckRepository checks, JsonMapper json) {
		this.docker = docker;
		this.courses = courses;
		this.sessions = sessions;
		this.checks = checks;
		this.json = json;
	}

	/** 절대 던지지 않는다. 컨테이너가 없거나 exec가 실패해도 결과로 표현한다. */
	public CheckResult check(StudentSession session) {
		String expectedPython = session.getCourse().getPythonVersion();
		Map<String, String> baseline = courses.baseline(session.getCourse());
		Observed observed;
		try {
			observed = observe(session);
		}
		catch (RuntimeException e) {
			log.warn("점검 중 예기치 않은 오류 session={}", session.getId(), e);
			observed = new Observed(false, null, null, 0);
		}
		return CheckComparator.compare(baseline, expectedPython, observed);
	}

	private Observed observe(StudentSession s) {
		String id = s.getContainerId();
		if (id == null || !safeRunning(s.getId(), id)) {
			return new Observed(false, null, null, 0);
		}
		long sid = s.getId() == null ? -1 : s.getId();
		return new Observed(true, pythonVersion(sid, id), installed(sid, id), ideConnections(sid, id));
	}

	private boolean safeRunning(Long sid, String id) {
		try {
			return docker.isRunning(id);
		}
		catch (RuntimeException e) {
			log.warn("점검 실패 session={} cmd=isRunning: {}", sid, e.getMessage());
			return false;
		}
	}

	private String pythonVersion(long sid, String id) {
		try {
			ExecResult r = docker.exec(id, List.of("/opt/venv/bin/python", "-c",
					"import platform;print(platform.python_version())"), EXEC_TIMEOUT);
			String out = r.stdout() == null ? "" : r.stdout().trim();
			if (r.exitCode() != 0 || out.isEmpty()) {
				log.warn("점검 실패 session={} cmd=python-version exit={}", sid, r.exitCode());
				return null;
			}
			return out;
		}
		catch (RuntimeException e) {
			log.warn("점검 실패 session={} cmd=python-version: {}", sid, e.getMessage());
			return null;
		}
	}

	private Map<String, String> installed(long sid, String id) {
		try {
			ExecResult r = docker.exec(id, List.of("uv", "pip", "list", "--python", "/opt/venv/bin/python",
					"--format", "json"), EXEC_TIMEOUT);
			if (r.exitCode() != 0) {
				log.warn("점검 실패 session={} cmd=uv-pip-list exit={}", sid, r.exitCode());
				return null;
			}
			List<Map<String, String>> list = json.readValue(r.stdout(), new TypeReference<List<Map<String, String>>>() {
			});
			Map<String, String> m = new HashMap<>();
			for (Map<String, String> p : list) {
				m.put(PackageNames.normalize(p.get("name")), p.get("version"));
			}
			return m;
		}
		catch (RuntimeException e) {
			log.warn("점검 실패 session={} cmd=uv-pip-list: {}", sid, e.getMessage());
			return null;
		}
	}

	private int ideConnections(long sid, String id) {
		try {
			// tcp6가 없으면 비정상 종료할 수 있지만 stdout에 나온 만큼은 센다.
			ExecResult r = docker.exec(id, List.of("cat", "/proc/net/tcp", "/proc/net/tcp6"), EXEC_TIMEOUT);
			return TcpConnections.countEstablished(r.stdout(), IDE_PORT);
		}
		catch (RuntimeException e) {
			log.warn("점검 실패 session={} cmd=proc-net-tcp: {}", sid, e.getMessage());
			return 0;
		}
	}

	/** 세션의 최신 상태를 갱신하고, 이전 결과와 달라졌을 때만 이력 행을 추가한다. */
	public CheckResult checkAndRecord(StudentSession session) {
		CheckResult result = check(session);
		String issuesJson = json.writeValueAsString(sortedIssues(result));
		String extrasJson = json.writeValueAsString(result.extras());
		EnvStatus status = result.ok() ? EnvStatus.OK : EnvStatus.MISMATCH;
		// 점검이 소유한 컬럼만 갱신한다. 동시에 바뀐 needsHelp 등을 덮지 않기 위해 엔티티 전체를 save하지 않는다.
		int updated = result.ideConnected()
				? sessions.updateCheckStateSeen(session.getId(), result.containerRunning(), true, status, issuesJson,
						extrasJson, Instant.now())
				: sessions.updateCheckState(session.getId(), result.containerRunning(), false, status, issuesJson,
						extrasJson);
		if (updated == 0) {
			// 세션이 사라졌다. 이력 행을 만들면 FK가 깨진다.
			return result;
		}
		boolean changed = checks.findFirstBySessionOrderByIdDesc(session)
				.map(prev -> prev.isOk() != result.ok() || !prev.getIssuesJson().equals(issuesJson)
						|| !prev.getExtrasJson().equals(extrasJson))
				.orElse(true);
		if (changed) {
			checks.save(new EnvironmentCheck(session, result.ok(), issuesJson, extrasJson));
		}
		return result;
	}

	private List<Issue> sortedIssues(CheckResult r) {
		return r.issues().stream()
				.sorted(java.util.Comparator.comparing((Issue i) -> i.type().name()).thenComparing(Issue::name))
				.toList();
	}
}
