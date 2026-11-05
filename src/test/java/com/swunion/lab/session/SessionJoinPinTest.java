package com.swunion.lab.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.swunion.lab.config.LabProperties;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.docker.DockerOperationException;
import com.swunion.lab.session.ContainerEnsurer.EnsureOutcome;
import com.swunion.lab.session.ContainerEnsurer.EnsureResult;
import com.swunion.lab.session.SessionServiceTest.FakeDocker;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * PIN 입장과 되찾기. 운영의 join은 트랜잭션 없이 돈다. 동시 입장과 고유 제약 위반을 그대로 재현하려고 테스트
 * 트랜잭션을 끄고(각 저장소 호출이 바로 커밋된다), 다른 테스트와 DB를 나누지 않도록 이 클래스만의 컨텍스트를 쓰며,
 * 매 테스트 뒤 직접 지운다.
 */
@DataJpaTest(properties = "lab.test.isolated-context=session-join-pin")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ExtendWith(OutputCaptureExtension.class)
class SessionJoinPinTest {

	static final Instant T0 = Instant.parse("2026-11-05T09:00:00Z");
	static final String CODE = "ABC123";

	@Autowired
	SessionRepository sessions;
	@Autowired
	CourseRepository courses;
	@Autowired
	DataSource dataSource;

	final FakeDocker docker = new FakeDocker();
	final MutableClock clock = new MutableClock(T0);
	final List<Integer> probedPorts = Collections.synchronizedList(new ArrayList<>());
	LabProperties props;
	RecordingEnsurer ensurer;
	PinPolicy pins;
	SessionService service;
	Course ready;

	@BeforeEach
	void setUp() {
		props = new LabProperties("i", "p", "m", 15, "http://lab.example.com:8080", "127.0.0.1", Path.of("."),
				"b:1", "t/course-", "swunion-test-s");
		pins = new PinPolicy(clock);
		ensurer = new RecordingEnsurer(sessions, docker, new ContainerSpecs(props), clock);
		service = serviceOn(sessions);
		Course c = new Course("수업", "3.12", "six==1.16.0");
		c.setStatus(CourseStatus.READY);
		c.setEntryCode(CODE);
		c.setImageName("t/course-1:1");
		ready = courses.save(c);
	}

	@AfterEach
	void cleanUp() {
		sessions.deleteAll();
		courses.deleteAll();
	}

	private SessionService serviceOn(SessionRepository repository) {
		HealthWaiter waiter = (port, timeout) -> {
			probedPorts.add(port);
			return true;
		};
		return SessionServices.create(repository, courses, docker, props, waiter, JsonMapper.builder().build(), clock,
				ensurer);
	}

	private StudentSession row(long id) {
		return sessions.findById(id).orElseThrow();
	}

	private JoinException rejected(String name, String pin) {
		try {
			service.join(CODE, name, pin);
		}
		catch (JoinException e) {
			return e;
		}
		throw new AssertionError("입장이 거절되어야 한다: " + name);
	}

	private void failTimes(String name, int times) {
		for (int i = 0; i < times; i++) {
			assertThat(rejected(name, "0000").status()).isEqualTo(403);
		}
	}

	/** 이 기능 전에 만든 행: 이름 키와 PIN이 없다. 컨테이너는 돌고 있다. */
	private StudentSession legacy(String name, String token, String containerId) {
		StudentSession s = new StudentSession(ready, name, token, "pw-legacy-24-chars-ABCDE");
		s.setContainerId(containerId);
		s.setVolumeName("swunion-test-s-legacy-" + containerId);
		s.setHostPort(41000);
		s.setContainerRunning(true);
		docker.ports.put(containerId, 41000);
		return sessions.save(s);
	}

	private void restartIdsAt(long next) throws SQLException {
		try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
			st.execute("ALTER TABLE student_session ALTER COLUMN id RESTART WITH " + next);
		}
	}

	@Test
	void newNameCreatesWithHashedPin() {
		JoinResult r = service.join(CODE, "김철수", "1234");

		assertThat(r.resumed()).isFalse();
		StudentSession row = row(r.session().getId());
		assertThat(row.getPinHash()).isNotNull().isNotEqualTo("1234").startsWith("$2");
		assertThat(pins.matches("1234", row.getPinHash())).isTrue();
		assertThat(pins.matches("4321", row.getPinHash())).isFalse();
		assertThat(row.getNameKey()).isEqualTo("김철수");
		assertThat(row.getDisplayName()).isEqualTo("김철수");
		assertThat(row.getFailedPinAttempts()).isZero();
		assertThat(row.getPinLockedUntil()).isNull();
		assertThat(r.session().getToken()).isEqualTo(row.getToken());
		assertThat(row.getContainerId()).isNotNull();
		assertThat(docker.specs).hasSize(1);
		assertThat(ensurer.ensured).as("새 입장은 ensureRunning이 아니라 직접 만든다").isEmpty();
		assertThat(r.toString()).doesNotContain(row.getPinHash()).doesNotContain(row.getToken())
				.doesNotContain(row.getIdePassword());
		assertThat(row.toString()).doesNotContain(row.getPinHash()).doesNotContain(row.getToken());
	}

	@Test
	void rightPinResumesAndRotatesToken() {
		JoinResult first = service.join(CODE, "김철수", "1234");
		String oldToken = first.session().getToken();

		JoinResult again = service.join(CODE, "김철수", "1234");

		assertThat(again.resumed()).isTrue();
		assertThat(again.session().getId()).isEqualTo(first.session().getId());
		assertThat(again.session().getToken()).matches("[0-9a-f]{64}").isNotEqualTo(oldToken);
		assertThatThrownBy(() -> service.byToken(oldToken)).isInstanceOf(SessionNotFoundException.class);
		assertThat(service.byToken(again.session().getToken()).getId()).isEqualTo(first.session().getId());
		assertThat(again.session().getIdePassword()).as("IDE 비밀번호는 그대로").isEqualTo(first.session().getIdePassword());
		assertThat(again.session().getVolumeName()).isEqualTo(first.session().getVolumeName());
		assertThat(sessions.count()).isEqualTo(1);
		assertThat(docker.specs).as("새 컨테이너를 만들지 않는다").hasSize(1);
	}

	@Test
	void nameKeyIgnoresCaseAndSpaces() {
		JoinResult first = service.join(CODE, "Kim", "1234");
		long id = first.session().getId();

		assertThat(service.join(CODE, " kim ", "1234").session().getId()).isEqualTo(id);
		// 한글 입력기에서 자주 들어가는 전각 공백도 앞뒤 공백으로 본다.
		JoinResult upper = service.join(CODE, "KIM　", "1234");
		assertThat(upper.resumed()).isTrue();
		assertThat(upper.session().getId()).isEqualTo(id);
		assertThat(rejected("kIM", "0000").status()).as("같은 학생이라 PIN 확인을 탄다").isEqualTo(403);

		assertThat(sessions.count()).isEqualTo(1);
		assertThat(row(id).getDisplayName()).as("처음 입력한 표시 이름은 그대로").isEqualTo("Kim");
		assertThat(row(id).getNameKey()).isEqualTo("kim");
		assertThat(docker.specs).hasSize(1);
	}

	@Test
	void nameKeyTreatsDecomposedHangulAsTheSameName() {
		// macOS 등에서 복사한 한글은 자모가 풀린 NFD일 수 있다. 화면에는 똑같이 보인다.
		String composed = "김철수";
		String decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD);
		assertThat(decomposed).isNotEqualTo(composed);
		long id = service.join(CODE, composed, "1234").session().getId();

		JoinResult again = service.join(CODE, " " + decomposed + " ", "1234");

		assertThat(again.resumed()).isTrue();
		assertThat(again.session().getId()).isEqualTo(id);
		assertThat(rejected(decomposed, "0000").status()).as("같은 학생이라 PIN 확인을 탄다").isEqualTo(403);
		assertThat(sessions.count()).isEqualTo(1);
		assertThat(SessionService.nameKey(decomposed)).isEqualTo(composed);
		// 반대로 NFD로 처음 들어온 이름도 NFC 키로 저장되어 NFC 입력과 맞는다.
		long other = service.join(CODE, Normalizer.normalize("이영희", Normalizer.Form.NFD), "5678").session().getId();
		assertThat(row(other).getNameKey()).isEqualTo("이영희");
		assertThat(service.join(CODE, "이영희", "5678").session().getId()).isEqualTo(other);
		assertThat(docker.specs).hasSize(2);
	}

	@Test
	void wrongPinCountsDown() {
		JoinResult first = service.join(CODE, "김철수", "1234");
		long id = first.session().getId();

		JoinException e = rejected("김철수", "0000");

		assertThat(e.status()).isEqualTo(403);
		assertThat(e.getMessage()).contains("남은 기회 4번");
		assertThat(rejected("김철수", "1111").getMessage()).contains("남은 기회 3번");
		StudentSession row = row(id);
		assertThat(row.getFailedPinAttempts()).isEqualTo(2);
		assertThat(row.getPinLockedUntil()).isNull();
		assertThat(row.getToken()).as("틀리면 토큰을 바꾸지 않는다").isEqualTo(first.session().getToken());
		assertThat(ensurer.ensured).isEmpty();
		assertThat(sessions.count()).isEqualTo(1);
	}

	@Test
	void fiveFailuresLock() {
		long id = service.join(CODE, "김철수", "1234").session().getId();
		for (int i = 1; i < PinPolicy.MAX_FAILURES; i++) {
			JoinException e = rejected("김철수", "0000");
			assertThat(e.status()).isEqualTo(403);
			assertThat(e.getMessage()).contains("남은 기회 " + (PinPolicy.MAX_FAILURES - i) + "번");
		}
		JoinException fifth = rejected("김철수", "0000");
		assertThat(fifth.status()).isEqualTo(403);
		assertThat(fifth.getMessage()).contains("5분 뒤");
		assertThat(row(id).getFailedPinAttempts()).isEqualTo(5);
		assertThat(row(id).getPinLockedUntil()).isEqualTo(T0.plus(PinPolicy.LOCK));

		JoinException locked = rejected("김철수", "1234");
		assertThat(locked.status()).as("맞는 PIN이어도 잠긴 동안은 확인하지 않는다").isEqualTo(429);
		assertThat(locked.getMessage()).contains("5분 뒤");
		assertThat(row(id).getFailedPinAttempts()).isEqualTo(5);

		clock.set(T0.plusSeconds(59));
		assertThat(rejected("김철수", "1234").getMessage()).as("4분 1초 남음 → 올림").contains("5분 뒤");
		clock.set(T0.plus(Duration.ofMinutes(4)).plusSeconds(30));
		JoinException later = rejected("김철수", "1234");
		assertThat(later.status()).isEqualTo(429);
		assertThat(later.getMessage()).contains("1분 뒤");
		assertThat(ensurer.ensured).isEmpty();

		clock.set(T0.plus(PinPolicy.LOCK));
		JoinResult ok = service.join(CODE, "김철수", "1234");

		assertThat(ok.resumed()).isTrue();
		assertThat(row(id).getFailedPinAttempts()).isZero();
		assertThat(row(id).getPinLockedUntil()).isNull();
	}

	@Test
	void lockExpiryRestartsTheCounter() {
		long id = service.join(CODE, "김철수", "1234").session().getId();
		failTimes("김철수", PinPolicy.MAX_FAILURES);
		clock.set(T0.plus(PinPolicy.LOCK));

		JoinException e = rejected("김철수", "0000");

		assertThat(e.status()).isEqualTo(403);
		assertThat(e.getMessage()).contains("남은 기회 4번");
		assertThat(row(id).getFailedPinAttempts()).isEqualTo(1);
		assertThat(row(id).getPinLockedUntil()).isNull();
	}

	@Test
	void successResetsTheCounter() {
		long id = service.join(CODE, "김철수", "1234").session().getId();
		failTimes("김철수", 3);

		assertThat(service.join(CODE, "김철수", "1234").resumed()).isTrue();

		assertThat(row(id).getFailedPinAttempts()).isZero();
		assertThat(rejected("김철수", "0000").getMessage()).contains("남은 기회 4번");
	}

	@Test
	void resetPinLetsNextJoinSetPin() {
		long id = service.join(CODE, "김철수", "1234").session().getId();
		failTimes("김철수", PinPolicy.MAX_FAILURES);
		assertThat(rejected("김철수", "1234").status()).isEqualTo(429);

		service.resetPin(id);

		StudentSession cleared = row(id);
		assertThat(cleared.getPinHash()).isNull();
		assertThat(cleared.getFailedPinAttempts()).isZero();
		assertThat(cleared.getPinLockedUntil()).isNull();
		JoinResult adopted = service.join(CODE, "김철수", "9999");
		assertThat(adopted.resumed()).isTrue();
		assertThat(adopted.session().getId()).isEqualTo(id);
		assertThat(pins.matches("9999", row(id).getPinHash())).isTrue();
		assertThat(rejected("김철수", "1234").status()).isEqualTo(403);
		assertThat(service.join(CODE, "김철수", "9999").resumed()).isTrue();
		assertThat(sessions.count()).isEqualTo(1);
		assertThatThrownBy(() -> service.resetPin(id + 1000)).isInstanceOf(SessionNotFoundException.class);
	}

	@Test
	void legacyNoPinAdoptsPin() {
		StudentSession old = legacy("박영희", "legacy-token-0001", "c-legacy");
		assertThat(old.getNameKey()).isNull();
		assertThat(old.getPinHash()).isNull();

		JoinResult r = service.join(CODE, " 박영희 ", "5678");

		assertThat(r.resumed()).isTrue();
		assertThat(r.session().getId()).isEqualTo(old.getId());
		StudentSession row = row(old.getId());
		assertThat(pins.matches("5678", row.getPinHash())).isTrue();
		assertThat(row.getNameKey()).isEqualTo("박영희");
		assertThat(row.getToken()).isNotEqualTo("legacy-token-0001");
		assertThatThrownBy(() -> service.byToken("legacy-token-0001")).isInstanceOf(SessionNotFoundException.class);
		assertThat(ensurer.ensured).containsExactly(old.getId());
		assertThat(docker.specs).as("원래 컨테이너를 그대로 쓴다").isEmpty();
		assertThat(rejected("박영희", "1111").status()).isEqualTo(403);
		assertThat(sessions.count()).isEqualTo(1);
	}

	@Test
	void legacyDuplicatesPickNewest() throws SQLException {
		// id 순서와 넣는 순서를 일부러 엇갈리게 한다. "마지막에 읽힌 행"이 아니라 "가장 큰 id"를 고르는지 본다.
		restartIdsAt(7);
		StudentSession newest = legacy("철수", "legacy-token-0007", "c-legacy-7");
		restartIdsAt(3);
		// 이름 키가 없는 예전 행은 같은 수업에 둘 있어도 고유 제약에 걸리지 않는다(H2는 NULL끼리 다르게 본다).
		StudentSession older = legacy("철수", "legacy-token-0003", "c-legacy-3");
		assertThat(newest.getId()).isEqualTo(7L);
		assertThat(older.getId()).isEqualTo(3L);

		JoinResult r = service.join(CODE, "철수", "1234");

		assertThat(r.resumed()).isTrue();
		assertThat(r.session().getId()).isEqualTo(7L);
		assertThat(row(7).getNameKey()).isEqualTo("철수");
		assertThat(pins.matches("1234", row(7).getPinHash())).isTrue();
		StudentSession untouched = row(3);
		assertThat(untouched.getNameKey()).isNull();
		assertThat(untouched.getPinHash()).isNull();
		assertThat(untouched.getToken()).isEqualTo("legacy-token-0003");
		assertThat(ensurer.ensured).containsExactly(7L);
		assertThat(service.join(CODE, "철수", "1234").session().getId()).isEqualTo(7L);
	}

	@Test
	void databaseRejectsASecondRowWithTheSameNameKey() {
		sessions.save(new StudentSession(ready, "Kim", "kim", "tok-unique-0001", "pw", "h"));

		assertThatThrownBy(() -> sessions.save(new StudentSession(ready, "kim", "kim", "tok-unique-0002", "pw", "h")))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(sessions.count()).isEqualTo(1);
	}

	private record Race(JoinResult first, Object second) {
	}

	/**
	 * 첫 입장이 행을 넣고 컨테이너를 만드는 도중에 두 번째 입장이 같은 이름으로 들어와 끝나게 한다.
	 * 첫 입장이 이름 잠금을 컨테이너 생성 내내 쥐고 있으면 두 번째가 끝나지 못해 시간 초과로 실패한다.
	 */
	private Race raceOnCreate(String name, String firstPin, String secondPin) throws Exception {
		CountDownLatch firstCreating = new CountDownLatch(1);
		CountDownLatch secondDone = new CountDownLatch(1);
		AtomicBoolean firstCreate = new AtomicBoolean(true);
		// 첫 생성만 붙잡는다. 두 번째 입장이 (잘못해서) 컨테이너를 또 만들면 기다리지 않고 지나가 단언에서 걸린다.
		docker.onCreate = () -> {
			if (firstCreate.getAndSet(false)) {
				firstCreating.countDown();
				awaitQuietly(secondDone);
			}
		};
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<JoinResult> first = pool.submit(() -> service.join(CODE, name, firstPin));
			assertThat(firstCreating.await(10, TimeUnit.SECONDS)).as("첫 입장이 컨테이너 생성에 들어갔다").isTrue();
			Future<Object> second = pool.submit(() -> {
				try {
					return service.join(CODE, name, secondPin);
				}
				catch (JoinException e) {
					return e;
				}
				finally {
					secondDone.countDown();
				}
			});
			Object secondResult = second.get(10, TimeUnit.SECONDS);
			return new Race(first.get(10, TimeUnit.SECONDS), secondResult);
		}
		finally {
			stop(pool);
		}
	}

	/** 실패한 테스트의 스레드가 정리(@AfterEach) 뒤에 행을 쓰면 다음 테스트가 깨지므로 끝날 때까지 기다린다. */
	private static void stop(ExecutorService pool) throws InterruptedException {
		pool.shutdownNow();
		assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).as("테스트 스레드가 끝났다").isTrue();
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Test
	void concurrentSameNewNameWithSamePinMakesOneRowAndResumes() throws Exception {
		Race race = raceOnCreate("이영희", "1234", "1234");

		assertThat(race.first().resumed()).isFalse();
		assertThat(race.second()).isInstanceOf(JoinResult.class);
		JoinResult second = (JoinResult) race.second();
		long id = race.first().session().getId();
		assertThat(second.resumed()).isTrue();
		assertThat(second.session().getId()).isEqualTo(id);
		assertThat(sessions.count()).isEqualTo(1);
		assertThat(docker.createCalls).isEqualTo(1);
		// 만드는 중인 컨테이너를 되찾기가 다시 만들거나 "작업 공간 유실"로 보지 않는다.
		assertThat(ensurer.ensured).isEmpty();
		StudentSession row = row(id);
		assertThat(row.getWorkspaceLostAt()).isNull();
		assertThat(row.isNeedsHelp()).isFalse();
		assertThat(row.getContainerId()).isNotNull();
		// 같은 학생의 두 번 누르기: 두 응답 모두 지금 쓸 수 있는 토큰을 받는다.
		assertThat(race.first().session().getToken()).isEqualTo(second.session().getToken())
				.isEqualTo(row.getToken());
	}

	@Test
	void concurrentSameNewNameWithDifferentPinIsRejected() throws Exception {
		Race race = raceOnCreate("이영희", "1234", "9999");

		assertThat(race.first().resumed()).isFalse();
		assertThat(race.second()).isInstanceOfSatisfying(JoinException.class,
				e -> assertThat(e.status()).isEqualTo(403));
		long id = race.first().session().getId();
		assertThat(sessions.count()).isEqualTo(1);
		assertThat(docker.createCalls).isEqualTo(1);
		assertThat(row(id).getFailedPinAttempts()).isEqualTo(1);
		assertThat(service.byToken(race.first().session().getToken()).getId()).isEqualTo(id);
	}

	/**
	 * 이름 잠금을 공유하지 않는 두 서비스(서버 둘, 또는 잠금이 빗나간 경우)가 같은 새 이름을 동시에 넣는다.
	 * 진 쪽은 "없는 이름"으로 판단한 뒤 행을 넣다가 고유 제약에 걸리고, 그 위반을 이미 있는 이름으로 보고 PIN 흐름을 탄다.
	 */
	private Object joinLosingInsertRace(String winnerPin, String loserPin) {
		SessionRepository racing = mock(SessionRepository.class, delegatesTo(sessions));
		AtomicBoolean raced = new AtomicBoolean();
		doAnswer(inv -> {
			if (raced.compareAndSet(false, true)) {
				service.join(CODE, "최민수", winnerPin);
			}
			return sessions.save(inv.<StudentSession>getArgument(0));
		}).when(racing).save(any(StudentSession.class));
		SessionService otherServer = serviceOn(racing);
		try {
			Object result = otherServer.join(CODE, "최민수", loserPin);
			assertThat(raced).isTrue();
			return result;
		}
		catch (JoinException e) {
			assertThat(raced).isTrue();
			return e;
		}
	}

	@Test
	void insertLosingUniqueRaceWithSamePinResumes() {
		Object result = joinLosingInsertRace("1234", "1234");

		assertThat(result).isInstanceOf(JoinResult.class);
		JoinResult r = (JoinResult) result;
		assertThat(r.resumed()).isTrue();
		assertThat(sessions.count()).isEqualTo(1);
		assertThat(r.session().getId()).isEqualTo(sessions.findAll().get(0).getId());
		assertThat(docker.createCalls).as("진 쪽은 컨테이너를 만들지 않는다").isEqualTo(1);
		assertThat(ensurer.ensured).containsExactly(r.session().getId());
	}

	@Test
	void insertLosingUniqueRaceWithDifferentPinIs403() {
		Object result = joinLosingInsertRace("1234", "9999");

		assertThat(result).isInstanceOfSatisfying(JoinException.class, e -> assertThat(e.status()).isEqualTo(403));
		assertThat(sessions.count()).isEqualTo(1);
		assertThat(docker.createCalls).isEqualTo(1);
		assertThat(sessions.findAll().get(0).getFailedPinAttempts()).isEqualTo(1);
	}

	@Test
	void concurrentWrongPinsCannotExceedTheLimit() throws Exception {
		long id = service.join(CODE, "김철수", "1234").session().getId();
		int attempts = 12;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(attempts);
		List<Integer> statuses = new ArrayList<>();
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < attempts; i++) {
				String pin = String.format("%04d", 5000 + i);
				results.add(pool.submit(() -> {
					start.await();
					try {
						service.join(CODE, "김철수", pin);
						return 200;
					}
					catch (JoinException e) {
						return e.status();
					}
				}));
			}
			start.countDown();
			for (Future<Integer> f : results) {
				statuses.add(f.get(30, TimeUnit.SECONDS));
			}
		}
		finally {
			stop(pool);
		}

		// 동시에 몰려도 PIN을 실제로 확인하는 것은 MAX_FAILURES번뿐이고 나머지는 잠금으로 거절된다.
		assertThat(statuses).filteredOn(s -> s == 403).hasSize(PinPolicy.MAX_FAILURES);
		assertThat(statuses).filteredOn(s -> s == 429).hasSize(attempts - PinPolicy.MAX_FAILURES);
		assertThat(row(id).getFailedPinAttempts()).isEqualTo(PinPolicy.MAX_FAILURES);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "12a4", "123", "12345", " 1234", "1234 ", "١٢٣٤" })
	void invalidPin(String pin) {
		JoinException e = rejected("김철수", pin);

		assertThat(e.status()).isEqualTo(400);
		assertThat(e.getMessage()).contains("PIN");
		assertThat(sessions.count()).isZero();
		assertThat(docker.touched()).isFalse();
	}

	@Test
	void invalidPinForAnExistingNameIsNotCountedAsAFailure() {
		long id = service.join(CODE, "김철수", "1234").session().getId();

		assertThat(rejected("김철수", "12a4").status()).isEqualTo(400);

		assertThat(row(id).getFailedPinAttempts()).isZero();
	}

	@Test
	void resumeEnsuresContainer() {
		long id = service.join(CODE, "김철수", "1234").session().getId();
		probedPorts.clear();
		docker.running = true;

		service.join(CODE, "김철수", "1234");

		assertThat(ensurer.ensured).containsExactly(id);
		assertThat(ensurer.outcomes).containsExactly(EnsureOutcome.ALREADY_RUNNING);
		assertThat(probedPorts).as("이미 돌고 있으면 IDE 응답을 기다리지 않는다").isEmpty();
		assertThat(docker.restarted).isEmpty();
		assertThat(docker.specs).hasSize(1);
	}

	@Test
	void resumeWaitsForTheIdeAfterStartingAStoppedContainer() {
		JoinResult first = service.join(CODE, "김철수", "1234");
		String containerId = first.session().getContainerId();
		probedPorts.clear();
		docker.running = false;
		docker.onRestart = () -> docker.ports.put(containerId, 41999);

		JoinResult again = service.join(CODE, "김철수", "1234");

		assertThat(ensurer.outcomes).containsExactly(EnsureOutcome.STARTED);
		assertThat(docker.restarted).containsExactly(containerId);
		assertThat(probedPorts).containsExactly(41999);
		assertThat(again.session().getHostPort()).as("바뀐 포트를 돌려준다").isEqualTo(41999);
	}

	@Test
	void resumeRecreatesAMissingContainerOnTheSameVolumeAndWaits() {
		JoinResult first = service.join(CODE, "김철수", "1234");
		probedPorts.clear();
		docker.running = false;
		docker.containerExistsAnswer = false;

		JoinResult again = service.join(CODE, "김철수", "1234");

		assertThat(ensurer.outcomes).containsExactly(EnsureOutcome.RECREATED);
		assertThat(docker.specs).hasSize(2);
		assertThat(docker.specs.get(1).volumeName()).isEqualTo(first.session().getVolumeName());
		assertThat(probedPorts).containsExactly(again.session().getHostPort());
		assertThat(again.session().getHostPort()).isNotEqualTo(first.session().getHostPort());
	}

	@Test
	void resumeSucceedsEvenWhenTheContainerCannotBeStarted() {
		JoinResult first = service.join(CODE, "김철수", "1234");
		probedPorts.clear();
		docker.running = false;
		docker.restartFailure = new DockerOperationException("restart refused");

		JoinResult again = service.join(CODE, "김철수", "1234");

		assertThat(again.resumed()).isTrue();
		assertThat(again.session().getToken()).isNotEqualTo(first.session().getToken());
		assertThat(ensurer.outcomes).containsExactly(EnsureOutcome.FAILED);
		assertThat(docker.restarted).as("실제로 시작을 시도했다").containsExactly(first.session().getContainerId());
		assertThat(probedPorts).isEmpty();
	}

	@Test
	void studentDeletedWhileTheirContainerIsCreatedLeavesNoContainerOrVolume() {
		// 강사가 입장 도중의 학생을 지운 상황: 행은 컨테이너를 기록하기 전에 사라진다. 학생 삭제는 아직 기록되지 않은
		// 컨테이너를 모르므로, 기록하지 못한 입장 쪽이 자기가 만든 것을 치워야 한다.
		docker.onCreate = () -> sessions.deleteAll();

		JoinException e = rejected("김철수", "1234");

		assertThat(e.status()).isEqualTo(409);
		assertThat(sessions.count()).isZero();
		assertThat(docker.specs).hasSize(1);
		String containerId = "c" + docker.specs.get(0).labels().get("swunion.session");
		assertThat(docker.removed).as("이 입장이 만든 컨테이너").containsExactly(containerId);
		assertThat(docker.removedVolumes).as("이 입장 전용 볼륨").containsExactly(docker.specs.get(0).volumeName());
		assertThat(probedPorts).as("지워진 세션의 IDE는 기다리지 않는다").isEmpty();
	}

	@Test
	void endedCourseRejected() {
		JoinResult first = service.join(CODE, "김철수", "1234");
		long id = first.session().getId();
		courses.updateStatusIf(ready.getId(), CourseStatus.READY, CourseStatus.ENDED);

		JoinException returning = rejected("김철수", "1234");
		JoinException newcomer = rejected("새학생", "1234");

		assertThat(returning.status()).isEqualTo(400);
		assertThat(returning.getMessage()).isEqualTo(newcomer.getMessage()).contains("입장 코드를 확인해 주세요");
		assertThat(sessions.count()).isEqualTo(1);
		assertThat(row(id).getToken()).isEqualTo(first.session().getToken());
		assertThat(ensurer.ensured).isEmpty();
		assertThat(docker.specs).hasSize(1);
	}

	@Test
	void resumeRacingCourseEndStopsTheContainerItStarted() {
		JoinResult first = service.join(CODE, "김철수", "1234");
		long id = first.session().getId();
		String containerId = first.session().getContainerId();
		probedPorts.clear();
		docker.running = false;
		// 입장 확인(READY) 뒤, 컨테이너를 켜는 사이에 강사가 수업을 종료한 상황
		docker.onRestart = () -> courses.updateStatusIf(ready.getId(), CourseStatus.READY, CourseStatus.ENDED);

		JoinException e = rejected("김철수", "1234");

		assertThat(e.getMessage()).isEqualTo("종료된 수업입니다");
		assertThat(docker.stopped).containsExactly(containerId);
		assertThat(row(id).isContainerRunning()).isFalse();
		assertThat(probedPorts).isEmpty();
	}

	@Test
	void pinHashAndTokensNeverReachTheLog(CapturedOutput output) {
		long id = service.join(CODE, "김철수", "7391").session().getId();
		String firstToken = row(id).getToken();
		for (int i = 0; i < PinPolicy.MAX_FAILURES; i++) {
			rejected("김철수", "8462");
		}
		rejected("김철수", "7391");
		clock.set(T0.plus(PinPolicy.LOCK));
		service.join(CODE, "김철수", "7391");
		StudentSession row = row(id);

		String log = output.getAll();
		assertThat(log).as("잠금 로그가 실제로 찍혔다").contains("세션 " + id);
		assertThat(log).doesNotContainPattern("(?<!\\d)7391(?!\\d)").doesNotContainPattern("(?<!\\d)8462(?!\\d)")
				.doesNotContain(row.getPinHash()).doesNotContain(firstToken).doesNotContain(row.getToken());
	}

	/** 실제 ensureRunning을 그대로 하되 어느 세션에 몇 번 불렸고 결과가 무엇이었는지 남긴다. */
	static class RecordingEnsurer extends ContainerEnsurer {
		final List<Long> ensured = Collections.synchronizedList(new ArrayList<>());
		final List<EnsureOutcome> outcomes = Collections.synchronizedList(new ArrayList<>());

		RecordingEnsurer(SessionRepository sessions, FakeDocker docker, ContainerSpecs specs, Clock clock) {
			super(sessions, docker, specs, clock);
		}

		@Override
		public EnsureResult ensureRunning(StudentSession s) {
			ensured.add(s.getId());
			EnsureResult r = super.ensureRunning(s);
			outcomes.add(r.outcome());
			return r;
		}
	}

	/** 잠금 해제 시각을 테스트가 움직인다. */
	static class MutableClock extends Clock {
		private volatile Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void set(Instant now) {
			this.now = now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
