package com.swunion.lab.session;

import com.swunion.lab.config.LabProperties;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.docker.ContainerSpec;
import com.swunion.lab.docker.DockerGateway;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SessionService {

	private static final Logger log = LoggerFactory.getLogger(SessionService.class);
	static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(60);
	static final int IDE_PORT = 8080;
	// StudentSession.needsHelpReason 컬럼 길이
	private static final int MAX_REASON = 4000;
	// 0/O, 1/l/I처럼 헷갈리는 글자를 뺀 집합. 학생이 손으로 입력할 수 있다.
	private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

	private final SecureRandom random = new SecureRandom();
	private final SessionRepository sessions;
	private final CourseRepository courses;
	private final DockerGateway docker;
	private final LabProperties props;
	private final HealthWaiter health;
	private final JsonMapper json;

	public SessionService(SessionRepository sessions, CourseRepository courses, DockerGateway docker,
			LabProperties props, HealthWaiter health, JsonMapper json) {
		this.sessions = sessions;
		this.courses = courses;
		this.docker = docker;
		this.props = props;
		this.health = health;
		this.json = json;
	}

	// 컨테이너 기동을 기다리는 동안 DB 트랜잭션을 잡지 않도록 @Transactional을 쓰지 않는다.
	public StudentSession join(String entryCode, String displayName) {
		String name = displayName == null ? "" : displayName.trim();
		if (name.isEmpty() || name.length() > 30) {
			throw new JoinException("이름은 1자 이상 30자 이하로 입력해 주세요.");
		}
		String code = entryCode == null ? "" : entryCode.trim().toUpperCase();
		Course course = code.isEmpty() ? null : courses.findByEntryCode(code).orElse(null);
		if (course == null || course.getStatus() != CourseStatus.READY) {
			throw new JoinException("입장 코드를 확인해 주세요. 코드가 없거나 아직 준비되지 않은 수업입니다.");
		}

		StudentSession session = sessions.save(new StudentSession(course, name, newToken(), newPassword()));
		// 세션 id만으로 이름을 짓으면 DB가 초기화되어 id가 다시 1부터 시작할 때 남아 있는 옛 학생의
		// 볼륨·컨테이너와 겹친다. 8자리 무작위 접미사로 join마다 이름을 유일하게 만든다.
		String resourceName = props.containerNamePrefix() + session.getId() + "-" + randomSuffix();
		session.setVolumeName(resourceName);
		session = sessions.save(session);
		String containerId = null;
		long sid = session.getId();
		try {
			ContainerSpec spec = new ContainerSpec(resourceName, course.getImageName(),
					Map.of("PASSWORD", session.getIdePassword()), resourceName, "/home/coder/project",
					props.ideBindAddress(), IDE_PORT,
					Map.of("swunion.session", String.valueOf(sid), "swunion.course", String.valueOf(course.getId())));
			long t0 = System.nanoTime();
			containerId = docker.createAndStart(spec);
			int hostPort = docker.hostPort(containerId, IDE_PORT);
			// containerId가 들어가는 순간부터 점검과 에이전트가 이 행을 쓴다. 이후로는 엔티티 전체를 save하지 않는다.
			sessions.updateContainerCreated(sid, containerId, hostPort);
			session.setContainerId(containerId);
			session.setHostPort(hostPort);
			session.setContainerRunning(true);
			// 수업 종료는 READY→ENDED로 바꾼 다음 containerId가 있는 세션만 멈춘다. 위 입장 확인과 지금 사이에
			// 종료가 끼었다면 종료는 이 컨테이너를 못 봤을 수 있으니 직접 치운다. 순서상 둘 중 하나는 반드시 본다.
			if (courses.findStatusById(course.getId()).orElse(null) != CourseStatus.READY) {
				log.warn("세션 {}: 입장 중에 수업이 종료되어 방금 만든 컨테이너를 지웁니다.", sid);
				discard(sid, containerId, resourceName, true);
				throw new JoinException("종료된 수업입니다");
			}
			if (health.awaitHealthy(hostPort, HEALTH_TIMEOUT)) {
				long millis = Duration.ofNanos(System.nanoTime() - t0).toMillis();
				sessions.updateStartupMillis(sid, millis);
				session.setStartupMillis(millis);
			}
			else {
				log.warn("세션 {}의 code-server가 {}초 안에 응답하지 않았습니다.", sid, HEALTH_TIMEOUT.toSeconds());
			}
			return session;
		}
		catch (JoinException e) {
			throw e;
		}
		catch (RuntimeException e) {
			log.error("세션 {} 컨테이너 생성 실패", sid, e);
			// (createAndStart가 시작 단계에서 실패하면 게이트웨이가 스스로 컨테이너를 지운다.)
			discard(sid, containerId, resourceName, false);
			throw new JoinException("컨테이너를 만들지 못했습니다. 잠시 후 다시 시도해 주세요.", 500);
		}
	}

	/** 이번 join이 만든 것만 지운다. 컨테이너는 id로, 볼륨은 이 join 전용 이름이라 안전하다. */
	private void discard(long sid, String containerId, String volumeName, boolean stopFirst) {
		if (containerId != null) {
			if (stopFirst) {
				try {
					docker.stop(containerId);
				}
				catch (RuntimeException ex) {
					log.warn("세션 {} 컨테이너 중단 실패: {}", sid, ex.getMessage());
				}
			}
			try {
				docker.remove(containerId);
			}
			catch (RuntimeException ex) {
				log.warn("세션 {} 컨테이너 정리 실패: {}", sid, ex.getMessage());
			}
		}
		try {
			docker.removeVolume(volumeName);
		}
		catch (RuntimeException ex) {
			log.warn("세션 {} 볼륨 정리 실패: {}", sid, ex.getMessage());
		}
		try {
			sessions.deleteById(sid);
		}
		catch (RuntimeException ex) {
			log.warn("세션 {} 행 삭제 실패: {}", sid, ex.getMessage());
		}
	}

	public StudentSession byToken(String token) {
		return sessions.findByToken(token == null ? "" : token).orElseThrow(SessionNotFoundException::new);
	}

	// 아래 쓰기는 전부 자기 컬럼만 갱신한다. 점검·에이전트·학생 요청이 동시에 같은 세션을 쓰므로
	// 엔티티 전체를 save하면 다른 쪽이 바꾼 값을 덮어쓴다.

	public void flagNeedsHelp(long sessionId, String reason) {
		sessions.updateNeedsHelp(sessionId, true, reason);
	}

	/** 에이전트가 강사에게 넘길 때. signature가 같은 불일치는 점검이 다시 에이전트로 보내지 않는다. */
	public void flagNeedsHelp(long sessionId, String reason, String signature) {
		sessions.updateEscalation(sessionId, reason, signature);
	}

	/** 이미 있는 이유(예: 에이전트의 "재점검 실패: …")는 지우지 않고 " / reason"으로 덧붙인다. */
	public void addNeedsHelpReason(long sessionId, String reason) {
		sessions.appendNeedsHelpReason(sessionId, reason, MAX_REASON - " / ".length() - reason.length());
	}

	public void clearNeedsHelp(long sessionId) {
		sessions.updateNeedsHelp(sessionId, false, null);
	}

	public void markRepairing(long sessionId) {
		sessions.updateEnvStatus(sessionId, EnvStatus.REPAIRING);
	}

	/**
	 * 멈춘 컨테이너도 되살린다. 재시작하면 호스트 포트가 바뀔 수 있어 다시 읽어 저장한다.
	 * 재시작을 기다리는 동안 학생 호출 등이 세션을 바꿀 수 있으므로 포트와 실행 여부만 갱신한다.
	 * 종료된 수업의 컨테이너는 되살리지 않는다. 종료 뒤에는 점검이 돌지 않아 아무도 다시 멈추지 않는다.
	 */
	public boolean restartContainer(long sessionId) {
		StudentSession s = sessions.findById(sessionId).orElseThrow(SessionNotFoundException::new);
		CourseStatus status = courses.findStatusById(s.getCourse().getId()).orElse(null);
		if (status != CourseStatus.READY) {
			log.warn("세션 {}: 수업 상태가 {}라 컨테이너를 재시작하지 않습니다.", sessionId, status);
			return false;
		}
		docker.restart(s.getContainerId());
		int port = docker.hostPort(s.getContainerId(), IDE_PORT);
		sessions.updateContainerState(sessionId, port, true);
		return health.awaitHealthy(port, HEALTH_TIMEOUT);
	}

	public String ideUrl(StudentSession s) {
		String host = URI.create(props.publicBaseUrl()).getHost();
		return "http://" + (host == null ? "localhost" : host) + ":" + s.getHostPort() + "/";
	}

	public List<Object> issues(StudentSession s) {
		return json.readValue(s.getIssuesJson() == null ? "[]" : s.getIssuesJson(), new TypeReference<List<Object>>() {
		});
	}

	private String randomSuffix() {
		byte[] b = new byte[4];
		random.nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

	private String newToken() {
		byte[] b = new byte[32];
		random.nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

	private String newPassword() {
		StringBuilder sb = new StringBuilder(24);
		for (int i = 0; i < 24; i++) {
			sb.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
		}
		return sb.toString();
	}
}
