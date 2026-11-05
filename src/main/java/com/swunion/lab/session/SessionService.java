package com.swunion.lab.session;

import com.swunion.lab.check.IssueTexts;
import com.swunion.lab.config.LabProperties;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.docker.ContainerSpec;
import com.swunion.lab.docker.DockerGateway;
import java.net.URI;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SessionService {

	private static final Logger log = LoggerFactory.getLogger(SessionService.class);
	static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(60);
	// 0/O, 1/l/I처럼 헷갈리는 글자를 뺀 집합. 학생이 손으로 입력할 수 있다.
	private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

	private final SecureRandom random = new SecureRandom();
	private final SessionRepository sessions;
	private final CourseRepository courses;
	private final DockerGateway docker;
	private final LabProperties props;
	private final HealthWaiter health;
	private final JsonMapper json;
	private final ContainerSpecs specs;
	private final ContainerEnsurer ensurer;
	private final PinPolicy pins;
	// 수업·이름 키마다 하나이고 지우지 않는다. 형식 검사를 통과하고 READY 수업의 수업 코드를 댄 이름만 만들며,
	// 그런 새 이름은 어차피 세션 행과 컨테이너를 만들므로 이 맵이 먼저 문제가 되지는 않는다.
	private final ConcurrentHashMap<String, ReentrantLock> nameLocks = new ConcurrentHashMap<>();
	// 행은 넣었지만 아직 컨테이너를 기록하지 않은 세션 id. 이 서버의 메모리에만 있다(재시작하면 만들던 입장도 없다).
	private final Set<Long> creating = ConcurrentHashMap.newKeySet();

	public SessionService(SessionRepository sessions, CourseRepository courses, DockerGateway docker,
			LabProperties props, HealthWaiter health, JsonMapper json, ContainerSpecs specs,
			ContainerEnsurer ensurer, PinPolicy pins) {
		this.sessions = sessions;
		this.courses = courses;
		this.docker = docker;
		this.props = props;
		this.health = health;
		this.json = json;
		this.specs = specs;
		this.ensurer = ensurer;
		this.pins = pins;
	}

	/**
	 * 이름 잠금 안에서 정한 것. created면 방금 넣은 새 행(컨테이너는 아직 없다), 아니면 PIN 확인을 통과하고 토큰을
	 * 바꾼 기존 행이다. containerInProgress는 같은 이름의 첫 입장이 아직 그 행의 컨테이너를 만드는 중이라는 뜻이다.
	 */
	private record Claim(StudentSession session, boolean created, boolean containerInProgress) {
	}

	/**
	 * 수업 코드 + 이름 + PIN. 그 수업에 같은 이름(앞뒤 공백·대소문자 무시)이 없으면 새 세션을 만들고, 있으면 PIN을
	 * 확인해 원래 세션으로 돌려보낸다(토큰은 새로 발급, 컨테이너는 다시 돌게 한다).
	 * 컨테이너 기동과 IDE 응답을 기다리는 동안 DB 트랜잭션도 이름 잠금도 쥐지 않도록 @Transactional을 쓰지 않는다.
	 */
	public JoinResult join(String entryCode, String displayName, String pin) {
		// strip은 한글 입력기에서 섞여 들어오는 전각 공백까지 지운다.
		String name = displayName == null ? "" : displayName.strip();
		if (name.isEmpty() || name.length() > 30) {
			throw new JoinException("이름은 1자 이상 30자 이하로 입력해 주세요.");
		}
		pins.validate(pin);
		Course course = readyCourse(entryCode);
		Claim claim = claim(course, name, nameKey(name), pin);
		return claim.created() ? startNewSession(claim.session(), course) : resume(claim, course);
	}

	/** 강사의 PIN 초기화. PIN 해시, 실패 횟수, 잠금을 비워 다음 입장이 새 PIN을 정하게 한다. */
	public void resetPin(long sessionId) {
		if (sessions.resetPin(sessionId) == 0) {
			throw new SessionNotFoundException();
		}
		log.info("세션 {}: PIN을 초기화했습니다.", sessionId);
	}

	private Course readyCourse(String entryCode) {
		String code = entryCode == null ? "" : entryCode.trim().toUpperCase();
		Course course = code.isEmpty() ? null : courses.findByEntryCode(code).orElse(null);
		if (course == null || course.getStatus() != CourseStatus.READY) {
			throw new JoinException("수업 코드를 확인해 주세요. 코드가 없거나 아직 준비되지 않은 수업입니다.");
		}
		return course;
	}

	// 붙여 넣은 한글은 자모가 풀린 NFD일 수 있다(macOS에서 복사한 글자 등). 화면에는 같아 보이므로 NFC로 맞춘다.
	static String nameKey(String displayName) {
		return displayName == null ? ""
				: Normalizer.normalize(displayName, Normalizer.Form.NFC).strip().toLowerCase(Locale.ROOT);
	}

	/**
	 * 같은 수업·같은 이름 키의 입장을 이 서버 안에서 한 줄로 세운다. 그래야 (1) 같은 새 이름으로 동시에 들어와도 행이
	 * 하나만 생기고 늦은 쪽이 PIN 확인을 타며, (2) 동시에 몰린 틀린 PIN도 잠금 전에 MAX_FAILURES번까지만 확인된다.
	 * 잠금은 행 조회·삽입, PIN 확인(BCrypt), 토큰 교체까지만 쥐고 컨테이너 작업과 IDE 대기 전에 놓는다.
	 */
	private Claim claim(Course course, String name, String key, String pin) {
		ReentrantLock lock = nameLocks.computeIfAbsent(course.getId() + ":" + key, k -> new ReentrantLock());
		lock.lock();
		try {
			StudentSession existing = findByNameKey(course.getId(), key);
			if (existing != null) {
				return verify(existing, key, pin);
			}
			try {
				return new Claim(insert(course, name, key, pin), true, false);
			}
			catch (DataIntegrityViolationException e) {
				// 잠금은 이 서버 안에서만 통한다. 그 밖에서 같은 이름이 먼저 들어갔으면 고유 제약에 걸린다.
				// 그 행이 보이면 "이미 있는 이름"으로 보고 PIN 확인을 탄다.
				StudentSession winner = findByNameKey(course.getId(), key);
				if (winner == null) {
					// 메시지에 제약 위반 값(토큰일 수도 있다)이 실리므로 종류만 남긴다.
					log.error("수업 {}: 세션 행을 넣지 못했습니다 ({}).", course.getId(), e.getClass().getSimpleName());
					throw new JoinException("입장하지 못했습니다. 잠시 후 다시 시도해 주세요.", 500);
				}
				return verify(winner, key, pin);
			}
		}
		finally {
			lock.unlock();
		}
	}

	/**
	 * 이름 키가 같은 행 중 id가 가장 큰 것(가장 최근에 만든 것). 이 기능 전에 만든 행은 이름 키가 없어 표시 이름으로
	 * 키를 만들어 비교한다. 그런 행은 같은 이름으로 여럿일 수 있다.
	 */
	private StudentSession findByNameKey(long courseId, String key) {
		return sessions.findByCourse_Id(courseId).stream()
				.filter(s -> key.equals(s.getNameKey() != null ? s.getNameKey() : nameKey(s.getDisplayName())))
				.max(Comparator.comparing(StudentSession::getId))
				.orElse(null);
	}

	private StudentSession insert(Course course, String name, String key, String pin) {
		// 컨테이너 키는 새 행과 함께 정해지고 이후 바뀌지 않는다(되찾기는 토큰만 바꾼다).
		StudentSession session = sessions.save(
				new StudentSession(course, name, key, newToken(), newPassword(), pins.hash(pin), newIdeKey()));
		long sid = session.getId();
		// 세션 id만으로 이름을 지으면 DB가 초기화되어 id가 다시 1부터 시작할 때 남아 있는 옛 학생의
		// 볼륨·컨테이너와 겹친다. 8자리 무작위 접미사로 join마다 이름을 유일하게 만든다.
		String resourceName = props.containerNamePrefix() + sid + "-" + randomSuffix();
		sessions.updateVolumeName(sid, resourceName);
		session.setVolumeName(resourceName);
		// 이름 잠금을 놓기 전에 표시한다. 잠금 뒤에 같은 이름으로 되찾는 쪽이 이 행을 보면 이 표시도 반드시 본다.
		creating.add(sid);
		return session;
	}

	/** 이름 잠금 안에서 부른다. 잠겨 있으면 PIN을 확인하지 않는다. 통과하면 토큰을 바꾼다. */
	private Claim verify(StudentSession s, String key, String pin) {
		long sid = s.getId();
		Duration locked = pins.lockRemaining(s.getPinLockedUntil());
		if (locked != null) {
			throw pins.locked(locked);
		}
		int failures = s.getFailedPinAttempts();
		if (s.getPinLockedUntil() != null) {
			// 잠금이 풀렸다. 실패 횟수를 처음부터 다시 센다.
			sessions.clearPinFailures(sid);
			failures = 0;
		}
		if (s.getPinHash() == null) {
			// 강사가 초기화했거나 이 기능 전에 만든 세션이다. 받은 PIN을 이 세션의 새 PIN으로 정한다.
			sessions.setPin(sid, pins.hash(pin));
			log.info("세션 {}: PIN을 새로 정했습니다.", sid);
		}
		else if (pins.matches(pin, s.getPinHash())) {
			if (failures > 0) {
				sessions.clearPinFailures(sid);
			}
		}
		else {
			sessions.recordPinFailure(sid, pins.lockUntilFromNow());
			int now = sessions.findFailedPinAttempts(sid).orElse(failures + 1);
			if (now >= PinPolicy.MAX_FAILURES) {
				log.warn("세션 {}: PIN을 {}번 연속 틀려 {}분 동안 잠급니다.", sid, now, PinPolicy.LOCK.toMinutes());
			}
			else {
				log.info("세션 {}: PIN 실패 {}번째", sid, now);
			}
			throw pins.wrongPin(now);
		}
		if (s.getNameKey() == null) {
			sessions.setNameKey(sid, key);
		}
		// 공용 PC에 남은 예전 학생 화면이 계속 열리지 않도록 돌아올 때마다 토큰을 바꾼다.
		sessions.rotateToken(sid, newToken());
		return new Claim(s, false, creating.contains(sid));
	}

	/** 새 행의 컨테이너를 만들고 IDE가 응답할 때까지(최대 60초) 기다린다. 이름 잠금 밖에서 돈다. */
	private JoinResult startNewSession(StudentSession session, Course course) {
		long sid = session.getId();
		long t0 = System.nanoTime();
		int hostPort;
		try {
			hostPort = createContainer(session, course);
		}
		finally {
			// 실패했으면 행까지 지운 뒤에 표시를 거둔다. 그 사이 되찾기가 반쯤 지워진 행에 컨테이너를 만들지 않게 한다.
			creating.remove(sid);
		}
		if (health.awaitHealthy(hostPort, HEALTH_TIMEOUT)) {
			sessions.updateStartupMillis(sid, Duration.ofNanos(System.nanoTime() - t0).toMillis());
		}
		else {
			log.warn("세션 {}의 code-server가 {}초 안에 응답하지 않았습니다.", sid, HEALTH_TIMEOUT.toSeconds());
		}
		// 기다리는 사이 같은 학생이 다시 눌러 토큰이 바뀌었을 수 있다. 지금 쓸 수 있는 토큰을 돌려준다.
		return new JoinResult(current(sid), false);
	}

	/** @return IDE 호스트 포트 */
	private int createContainer(StudentSession session, Course course) {
		long sid = session.getId();
		String resourceName = session.getVolumeName();
		String containerId = null;
		try {
			ContainerSpec spec = specs.forSession(session, course);
			containerId = docker.createAndStart(spec);
			int hostPort = docker.hostPort(containerId, ContainerSpecs.IDE_PORT);
			// containerId가 들어가는 순간부터 점검과 에이전트가 이 행을 쓴다. 이후로는 엔티티 전체를 save하지 않는다.
			if (sessions.updateContainerCreated(sid, containerId, hostPort) == 0) {
				// 만드는 사이 강사가 이 학생을 지웠다. 학생 삭제는 아직 기록되지 않은 이 컨테이너를 모르므로 여기서
				// 치운다. 볼륨도 이 입장 전용 이름이고 행이 없으니 쓸 사람이 없다.
				log.warn("세션 {}: 컨테이너를 만드는 사이 세션이 지워져 방금 만든 컨테이너와 볼륨을 지웁니다.", sid);
				discard(sid, containerId, resourceName, false);
				throw sessionGone();
			}
			// 수업 종료는 READY→ENDED로 바꾼 다음 containerId가 있는 세션만 멈춘다. 위 입장 확인과 지금 사이에
			// 종료가 끼었다면 종료는 이 컨테이너를 못 봤을 수 있으니 직접 치운다. 순서상 둘 중 하나는 반드시 본다.
			if (courses.findStatusById(course.getId()).orElse(null) != CourseStatus.READY) {
				log.warn("세션 {}: 입장 중에 수업이 종료되어 방금 만든 컨테이너를 지웁니다.", sid);
				discard(sid, containerId, resourceName, true);
				throw new JoinException("종료된 수업입니다");
			}
			return hostPort;
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

	/**
	 * 되찾은 세션의 컨테이너가 돌게 한다. 켜거나 다시 만들었으면 IDE 응답을 기다린다(최대 60초).
	 * 컨테이너를 돌게 하지 못해도 되찾기는 성공으로 돌려준다. 학생은 새 토큰으로 화면을 보고, IDE 중단은 점검과
	 * 에이전트가 이어서 다룬다.
	 */
	private JoinResult resume(Claim claim, Course course) {
		StudentSession s = claim.session();
		long sid = s.getId();
		if (claim.containerInProgress()) {
			// 같은 이름의 첫 입장이 아직 컨테이너를 만드는 중이다. 여기서 또 만들면 이름이 충돌하고, 볼륨이 아직 없어
			// "작업 공간 유실"로 잘못 기록된다. 만드는 쪽이 끝내게 둔다.
			log.info("세션 {}: 컨테이너를 만드는 중이라 기다리지 않고 돌려줍니다.", sid);
			return new JoinResult(current(sid), true);
		}
		ContainerEnsurer.EnsureResult result = ensurer.ensureRunning(s);
		switch (result.outcome()) {
			case ALREADY_RUNNING -> {
			}
			case FAILED -> log.warn("세션 {}: 되찾기에서 컨테이너를 돌게 하지 못했습니다.", sid);
			default -> {
				// 입장 확인 뒤 컨테이너를 켜는 사이에 수업이 종료됐다면 종료는 이 컨테이너를 못 봤을 수 있다.
				if (courses.findStatusById(course.getId()).orElse(null) != CourseStatus.READY) {
					log.warn("세션 {}: 되찾는 중에 수업이 종료되어 방금 켠 컨테이너를 멈춥니다.", sid);
					stopQuietly(sid);
					throw new JoinException("종료된 수업입니다");
				}
				if (!health.awaitHealthy(result.hostPort(), HEALTH_TIMEOUT)) {
					log.warn("세션 {}의 code-server가 {}초 안에 응답하지 않았습니다.", sid, HEALTH_TIMEOUT.toSeconds());
				}
			}
		}
		return new JoinResult(current(sid), true);
	}

	/** 되찾기가 켠 컨테이너를 멈춘다. 다시 만들었으면 id가 바뀌었으므로 DB에서 다시 읽는다. */
	private void stopQuietly(long sid) {
		String containerId = sessions.findById(sid).map(StudentSession::getContainerId).orElse(null);
		if (containerId != null) {
			try {
				docker.stop(containerId);
			}
			catch (RuntimeException e) {
				log.warn("세션 {} 컨테이너 중단 실패: {}", sid, e.getMessage());
			}
		}
		sessions.markStopped(sid);
	}

	private StudentSession current(long sid) {
		return sessions.findById(sid).orElseThrow(SessionService::sessionGone);
	}

	private static JoinException sessionGone() {
		return new JoinException("입장하는 사이 세션이 삭제되었습니다. 다시 입장해 주세요.", 409);
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

	/**
	 * IDE 안 확장이 보낸 컨테이너 키로 세션을 찾는다. 빈 값은 저장소를 부르지 않고 거절한다.
	 * 키 없는 예전 세션(ideKey null)이 빈 값 조회에 걸리지 않게 하려는 것이기도 하다.
	 * 키가 256비트 무작위 값이라 DB 동등 비교로 찾아도 타이밍으로 알아낼 수 없다.
	 */
	public StudentSession byIdeKey(String key) {
		if (key == null || key.isBlank()) {
			throw new SessionNotFoundException();
		}
		return sessions.findByIdeKey(key).orElseThrow(SessionNotFoundException::new);
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
		sessions.addNeedsHelpReason(sessionId, reason);
	}

	public void clearNeedsHelp(long sessionId) {
		sessions.updateNeedsHelp(sessionId, false, null);
	}

	public void markRepairing(long sessionId) {
		sessions.updateEnvStatus(sessionId, EnvStatus.REPAIRING);
	}

	/**
	 * 컨테이너를 다시 시작한다. 멈춘 컨테이너도 되살리고, Docker에서 사라졌으면 같은 볼륨으로 새로 만든다.
	 * 실제 시작과 포트·실행 여부 저장은 {@link ContainerEnsurer}가 하고, 여기서는 수업 상태 확인과 IDE 응답 대기만 한다.
	 * 종료된 수업의 컨테이너는 되살리지 않는다. 종료 뒤에는 점검이 돌지 않아 아무도 다시 멈추지 않는다.
	 * 시작이나 재생성이 실패하면 예외 대신 false를 돌려준다.
	 */
	public boolean restartContainer(long sessionId) {
		StudentSession s = sessions.findById(sessionId).orElseThrow(SessionNotFoundException::new);
		CourseStatus status = courses.findStatusById(s.getCourse().getId()).orElse(null);
		if (status != CourseStatus.READY) {
			log.warn("세션 {}: 수업 상태가 {}라 컨테이너를 재시작하지 않습니다.", sessionId, status);
			return false;
		}
		// 에이전트의 restart_ide는 돌고 있어도 IDE가 응답하지 않을 때 쓰므로 이미 돌고 있어도 재시작한다.
		ContainerEnsurer.EnsureResult result = ensurer.restart(s);
		if (result.outcome() == ContainerEnsurer.EnsureOutcome.FAILED) {
			return false;
		}
		return health.awaitHealthy(result.hostPort(), HEALTH_TIMEOUT);
	}

	/** 기본은 학생 브라우저가 IDE 포트로 바로 접속하는 주소다. 로컬 개발에서는 localhost라 안전한 연결로 취급된다. */
	public static final String DEFAULT_IDE_URL_TEMPLATE = "http://{host}:{port}/";

	// http로 localhost가 아닌 주소에 접속하면 브라우저가 안전한 연결로 보지 않아 VS Code webview(실습 도우미 패널)가
	// 뜨지 않는다. 배포에서는 HTTPS 리버스 프록시 아래 경로({base}/ide/{port}/)로 열도록 바꾼다.
	private String ideUrlTemplate = DEFAULT_IDE_URL_TEMPLATE;

	@Value("${lab.ide-url-template:" + DEFAULT_IDE_URL_TEMPLATE + "}")
	public void setIdeUrlTemplate(String template) {
		if (template == null || !template.contains("{port}")) {
			throw new IllegalArgumentException("lab.ide-url-template에는 {port}가 있어야 합니다: " + template);
		}
		this.ideUrlTemplate = template;
	}

	public String ideUrl(StudentSession s) {
		String base = props.publicBaseUrl().replaceAll("/+$", "");
		String host = URI.create(props.publicBaseUrl()).getHost();
		return ideUrlTemplate.replace("{base}", base).replace("{host}", host == null ? "localhost" : host)
				.replace("{port}", String.valueOf(s.getHostPort()));
	}

	public List<Object> issues(StudentSession s) {
		return json.readValue(s.getIssuesJson() == null ? "[]" : s.getIssuesJson(), new TypeReference<List<Object>>() {
		});
	}

	/** issues와 같은 점검 결과를 대시보드와 같은 한국어 문구로 풀어 쓴 것. */
	public List<String> issueTexts(StudentSession s) {
		return IssueTexts.fromJson(json, s.getIssuesJson(), s.getId());
	}

	private String randomSuffix() {
		byte[] b = new byte[4];
		random.nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

	private String newToken() {
		return randomHex32();
	}

	private String newIdeKey() {
		return randomHex32();
	}

	/** 무작위 32바이트의 소문자 16진 64자. */
	private String randomHex32() {
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
