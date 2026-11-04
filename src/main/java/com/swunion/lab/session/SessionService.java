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
		try {
			ContainerSpec spec = new ContainerSpec(resourceName, course.getImageName(),
					Map.of("PASSWORD", session.getIdePassword()), resourceName, "/home/coder/project",
					props.ideBindAddress(), IDE_PORT,
					Map.of("swunion.session", String.valueOf(session.getId()), "swunion.course",
							String.valueOf(course.getId())));
			long t0 = System.nanoTime();
			containerId = docker.createAndStart(spec);
			session.setContainerId(containerId);
			session.setHostPort(docker.hostPort(containerId, IDE_PORT));
			session.setContainerRunning(true);
			session = sessions.save(session);
			if (health.awaitHealthy(session.getHostPort(), HEALTH_TIMEOUT)) {
				session.setStartupMillis(Duration.ofNanos(System.nanoTime() - t0).toMillis());
				session = sessions.save(session);
			}
			else {
				log.warn("세션 {}의 code-server가 {}초 안에 응답하지 않았습니다.", session.getId(), HEALTH_TIMEOUT.toSeconds());
			}
			return session;
		}
		catch (RuntimeException e) {
			long sid = session.getId();
			log.error("세션 {} 컨테이너 생성 실패", sid, e);
			// 이번 join이 만든 것만 지운다. 컨테이너는 id로, 볼륨은 이 join 전용 이름이라 안전하다.
			// (createAndStart가 시작 단계에서 실패하면 게이트웨이가 스스로 컨테이너를 지운다.)
			if (containerId != null) {
				try {
					docker.remove(containerId);
				}
				catch (RuntimeException ex) {
					log.warn("세션 {} 컨테이너 정리 실패: {}", sid, ex.getMessage());
				}
			}
			try {
				docker.removeVolume(resourceName);
			}
			catch (RuntimeException ex) {
				log.warn("세션 {} 볼륨 정리 실패: {}", sid, ex.getMessage());
			}
			sessions.deleteById(sid);
			throw new JoinException("컨테이너를 만들지 못했습니다. 잠시 후 다시 시도해 주세요.", 500);
		}
	}

	public StudentSession byToken(String token) {
		return sessions.findByToken(token == null ? "" : token).orElseThrow(SessionNotFoundException::new);
	}

	public void flagNeedsHelp(long sessionId, String reason) {
		sessions.findById(sessionId).ifPresent(s -> {
			s.setNeedsHelp(true);
			s.setNeedsHelpReason(reason);
			sessions.save(s);
		});
	}

	public void clearNeedsHelp(long sessionId) {
		sessions.findById(sessionId).ifPresent(s -> {
			s.setNeedsHelp(false);
			s.setNeedsHelpReason(null);
			sessions.save(s);
		});
	}

	/** 멈춘 컨테이너도 되살린다. 재시작하면 호스트 포트가 바뀔 수 있어 다시 읽어 저장한다. */
	public boolean restartContainer(long sessionId) {
		StudentSession s = sessions.findById(sessionId).orElseThrow(SessionNotFoundException::new);
		docker.restart(s.getContainerId());
		s.setHostPort(docker.hostPort(s.getContainerId(), IDE_PORT));
		s.setContainerRunning(true);
		s = sessions.save(s);
		return health.awaitHealthy(s.getHostPort(), HEALTH_TIMEOUT);
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
