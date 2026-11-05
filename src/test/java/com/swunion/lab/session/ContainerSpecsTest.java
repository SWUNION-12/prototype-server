package com.swunion.lab.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.swunion.lab.config.LabProperties;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.docker.ContainerSpec;
import com.swunion.lab.session.ContainerEnsurer.EnsureOutcome;
import com.swunion.lab.session.ContainerEnsurer.EnsureResult;
import com.swunion.lab.session.SessionServiceTest.FakeDocker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 컨테이너 환경변수. 키가 있는 세션은 SWUNION_IDE_KEY를 받고, 서버 주소는 항상 들어간다.
 * 컨테이너를 다시 만들 때도 같은 명세 생성기를 쓰므로 같은 키가 다시 들어가야 한다(확장이 끊기지 않는다).
 */
@DataJpaTest
class ContainerSpecsTest {

	static final String KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
	static final String SERVER_URL = "http://lab-server.test:9090";
	static final String VOLUME = "swunion-test-s7-bbbb2222";

	@Autowired
	SessionRepository sessions;
	@Autowired
	CourseRepository courses;

	ContainerSpecs specs;
	Course course;
	LabProperties props;

	@BeforeEach
	void setUp() {
		props = new LabProperties("i", "p", "m", 15, "http://lab.example.com:8080", "127.0.0.1", Path.of("."),
				"b:1", "t/course-", "swunion-test-s", SERVER_URL);
		specs = new ContainerSpecs(props);
		Course c = new Course("수업", "3.12", "six==1.16.0");
		c.setStatus(CourseStatus.READY);
		c.setEntryCode("SPC001");
		c.setImageName("t/course-1:1");
		course = courses.save(c);
	}

	private StudentSession keyed(String key) {
		StudentSession s = new StudentSession(course, "철수", "철수", "tok-specs-keyed", "pw-24-chars-ABCDEFGHIJ", null,
				key);
		s.setVolumeName(VOLUME);
		return sessions.saveAndFlush(s);
	}

	@Test
	void withKey() {
		StudentSession s = keyed(KEY);

		ContainerSpec spec = specs.forSession(s, course);

		assertThat(spec.env()).isEqualTo(Map.of("PASSWORD", "pw-24-chars-ABCDEFGHIJ", "SWUNION_IDE_KEY", KEY,
				"SWUNION_SERVER_URL", SERVER_URL, "CODE_SERVER_COOKIE_SUFFIX", VOLUME));
	}

	@Test
	void cookieSuffixDiffersPerSession() {
		// 프록시 뒤에서는 모든 학생 IDE가 같은 주소를 쓰므로, 쿠키 이름이 같으면 한 브라우저에서 로그인이 서로 덮인다.
		StudentSession a = keyed(KEY);
		StudentSession b = new StudentSession(course, "영희", "tok-specs-other", "pw-other-24-chars-ABC");
		b.setVolumeName(VOLUME + "-other");

		assertThat(specs.forSession(a, course).env().get("CODE_SERVER_COOKIE_SUFFIX"))
				.isNotEqualTo(specs.forSession(b, course).env().get("CODE_SERVER_COOKIE_SUFFIX"));
	}

	@Test
	void legacyNoKey() {
		// 이 기능 전에 만든 행은 키가 없다. 키 없는 세션에 새 키를 만들어 주지 않는다.
		StudentSession s = new StudentSession(course, "영희", "tok-specs-legacy", "pw-legacy-24-chars-ABC");
		s.setVolumeName(VOLUME);

		ContainerSpec spec = specs.forSession(s, course);

		assertThat(spec.env()).doesNotContainKey("SWUNION_IDE_KEY");
		assertThat(spec.env()).isEqualTo(Map.of("PASSWORD", "pw-legacy-24-chars-ABC", "SWUNION_SERVER_URL", SERVER_URL,
				"CODE_SERVER_COOKIE_SUFFIX", VOLUME));
	}

	@Test
	void specToStringMasks() {
		StudentSession s = keyed(KEY);

		String printed = specs.forSession(s, course).toString();

		assertThat(printed).contains("SWUNION_IDE_KEY=***").doesNotContain(KEY).doesNotContain("pw-24-chars");
	}

	@Test
	void sameKeyOnRecreate() {
		// 되찾기·재개·재시작이 컨테이너를 다시 만드는 경로. 옛 컨테이너가 Docker에서 사라진 상태에서 시작한다.
		FakeDocker docker = new FakeDocker();
		docker.running = false;
		docker.containerExistsAnswer = false;
		StudentSession s = keyed(KEY);
		s.setContainerId("gone-container");
		s = sessions.saveAndFlush(s);
		ContainerEnsurer ensurer = new ContainerEnsurer(sessions, docker, specs,
				Clock.fixed(Instant.parse("2026-11-05T09:30:00Z"), ZoneOffset.UTC));

		EnsureResult r = ensurer.ensureRunning(s);

		assertThat(r.outcome()).isEqualTo(EnsureOutcome.RECREATED);
		assertThat(docker.specs).hasSize(1);
		assertThat(docker.specs.get(0).env()).containsEntry("SWUNION_IDE_KEY", KEY)
				.containsEntry("SWUNION_SERVER_URL", SERVER_URL);
		assertThat(sessions.findById(s.getId()).orElseThrow().getIdeKey()).as("DB의 키는 그대로").isEqualTo(KEY);
	}

	// ---- IDE 확인 방식(lab.ide-auth) ----

	@Test
	void passwordModeKeepsTheImageCmd() {
		ContainerSpec spec = specs.forSession(keyed(KEY), course);

		assertThat(specs.ideAuthMode()).isEqualTo(IdeAuthMode.PASSWORD);
		assertThat(spec.cmd()).as("이미지 CMD(--auth password)를 그대로 쓴다").isNull();
	}

	@Test
	void proxyModeTurnsOffOnlyTheCodeServerPassword() {
		ContainerSpecs proxy = new ContainerSpecs(props, IdeAuthMode.PROXY);
		StudentSession s = keyed(KEY);

		ContainerSpec spec = proxy.forSession(s, course);

		assertThat(spec.cmd()).containsExactly("--bind-addr", "0.0.0.0:8080", "--auth", "none", "--disable-telemetry",
				"--disable-update-check", "/home/coder/project");
		// 이름, 이미지, 볼륨, 포트, 환경은 비밀번호 방식과 같다.
		ContainerSpec password = specs.forSession(s, course);
		assertThat(spec.env()).isEqualTo(password.env());
		assertThat(spec.name()).isEqualTo(password.name());
		assertThat(spec.image()).isEqualTo(password.image());
		assertThat(spec.containerPort()).isEqualTo(password.containerPort());
		assertThat(spec.toString()).contains("--auth, none").doesNotContain("pw-24-chars");
	}

	// proxy 실행 인자는 기본 이미지 CMD에서 --auth password만 바꾼 것이어야 한다. Dockerfile의 CMD가 바뀌면 여기서 알린다.
	@Test
	void proxyCmdMatchesTheBaseImageCmdExceptAuth() throws Exception {
		String dockerfile = Files.readString(Path.of("docker/lab-base/Dockerfile"));
		Matcher m = Pattern.compile("(?m)^CMD\\s*(\\[.*\\])\\s*$").matcher(dockerfile);
		assertThat(m.find()).as("기본 이미지 CMD").isTrue();
		List<String> imageCmd = JsonMapper.builder().build().readValue(m.group(1), new TypeReference<List<String>>() {
		});
		assertThat(m.find()).as("CMD는 하나").isFalse();

		int auth = imageCmd.indexOf("--auth");
		assertThat(auth).isNotNegative();
		assertThat(imageCmd.get(auth + 1)).isEqualTo("password");
		assertThat(Collections.frequency(imageCmd, "--auth")).isEqualTo(1);
		List<String> expected = new ArrayList<>(imageCmd);
		expected.set(auth + 1, "none");
		assertThat(ContainerSpecs.PROXY_CMD).isEqualTo(expected);
	}

	@Test
	void settingIsPasswordOrProxyAndAnythingElseStopsStartup() {
		assertThat(IdeAuthMode.parse("password")).isEqualTo(IdeAuthMode.PASSWORD);
		assertThat(IdeAuthMode.parse(" proxy ")).isEqualTo(IdeAuthMode.PROXY);
		assertThat(IdeAuthMode.parse("PROXY")).isEqualTo(IdeAuthMode.PROXY);
		for (String bad : new String[] { "none", "", "off", "proxyy", "pass word" }) {
			assertThatThrownBy(() -> IdeAuthMode.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("password 또는 proxy");
		}
		assertThatThrownBy(() -> IdeAuthMode.parse(null)).isInstanceOf(IllegalArgumentException.class);

		ApplicationContextRunner runner = new ApplicationContextRunner()
				.withBean(LabProperties.class, () -> props)
				.withUserConfiguration(ContainerSpecs.class);
		runner.run(ctx -> assertThat(ctx.getBean(ContainerSpecs.class).ideAuthMode()).as("기본값")
				.isEqualTo(IdeAuthMode.PASSWORD));
		runner.withPropertyValues("lab.ide-auth=proxy")
				.run(ctx -> assertThat(ctx.getBean(ContainerSpecs.class).ideAuthMode()).isEqualTo(IdeAuthMode.PROXY));
		runner.withPropertyValues("lab.ide-auth=nonee").run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("password 또는 proxy");
		});
	}

	// proxy 방식은 Caddy만 IDE 포트에 닿는다는 전제다. 포트가 이 컴퓨터 밖으로 열리면 시작을 멈춘다(주소 값은 싣지 않는다).
	@Test
	void proxyWithIdePortsOpenBeyondThisMachineStopsStartup() {
		new ContainerSpecs(props, "proxy");
		var v6 = new LabProperties("i", "p", "m", 15, "http://lab.example.com:8080", "::1", Path.of("."), "b:1",
				"t/course-", "swunion-test-s", SERVER_URL);
		new ContainerSpecs(v6, "proxy");

		for (String bind : new String[] { "0.0.0.0", "192.168.0.5", "::", "localhost", "127.0.0.1.evil" }) {
			var open = new LabProperties("i", "p", "m", 15, "http://lab.example.com:8080", bind, Path.of("."), "b:1",
					"t/course-", "swunion-test-s", SERVER_URL);
			assertThat(new ContainerSpecs(open, "password").ideAuthMode()).isEqualTo(IdeAuthMode.PASSWORD);
			assertThatThrownBy(() -> new ContainerSpecs(open, "proxy")).as(bind)
					.isInstanceOf(IllegalStateException.class).hasMessageContaining("IDE_BIND_ADDRESS")
					.satisfies(e -> assertThat(e.getMessage()).doesNotContain(bind));
		}

		var open = new LabProperties("i", "p", "m", 15, "http://lab.example.com:8080", "0.0.0.0", Path.of("."), "b:1",
				"t/course-", "swunion-test-s", SERVER_URL);
		new ApplicationContextRunner().withBean(LabProperties.class, () -> open)
				.withUserConfiguration(ContainerSpecs.class).withPropertyValues("lab.ide-auth=proxy")
				.run(ctx -> assertThat(ctx).hasFailed());
	}

	// 설정을 proxy에서 password로 되돌렸다. 비밀번호 없이 만든 컨테이너를 그대로 켜면 --auth none이 남으므로, 돌고
	// 있든 멈췄든 지우고 같은 볼륨으로 비밀번호 방식 컨테이너를 만든다.
	@Test
	void proxyContainerIsReplacedWhenTheSettingIsBackToPassword() {
		Clock clock = Clock.fixed(Instant.parse("2026-11-05T09:30:00Z"), ZoneOffset.UTC);
		for (boolean restart : new boolean[] { false, true }) {
			StudentSession s = sessions.saveAndFlush(withVolume(new StudentSession(course, "프록시" + restart,
					"tok-replace-" + restart, "pw-replace-24-chars-ABC")));
			sessions.updateContainerCreated(s.getId(), "old-proxy-" + restart, 40700, IdeAuthMode.PROXY);
			FakeDocker docker = new FakeDocker();
			docker.running = true;
			ContainerEnsurer ensurer = new ContainerEnsurer(sessions, docker, specs, clock);
			StudentSession recorded = sessions.findById(s.getId()).orElseThrow();

			EnsureResult r = restart ? ensurer.restart(recorded) : ensurer.ensureRunning(recorded);

			assertThat(r.outcome()).as("restart=%s", restart).isEqualTo(EnsureOutcome.RECREATED);
			assertThat(docker.removed).containsExactly("old-proxy-" + restart);
			assertThat(docker.restarted).isEmpty();
			assertThat(docker.specs).hasSize(1);
			assertThat(docker.specs.get(0).cmd()).as("이미지 CMD(--auth password)").isNull();
			assertThat(docker.specs.get(0).volumeName()).isEqualTo(s.getVolumeName());
			assertThat(docker.removedVolumes).as("학생 코드 볼륨은 남는다").isEmpty();
			StudentSession after = sessions.findById(s.getId()).orElseThrow();
			assertThat(after.getIdeAuthMode()).isEqualTo(IdeAuthMode.PASSWORD);
			assertThat(after.getContainerId()).isNotEqualTo("old-proxy-" + restart);
		}
	}

	// 반대 방향(비밀번호 컨테이너를 proxy 설정에서 켜기)은 비밀번호가 더해질 뿐이라 지금처럼 그대로 쓴다(spec 4-3).
	@Test
	void passwordContainerIsKeptUnderProxySetting() {
		Clock clock = Clock.fixed(Instant.parse("2026-11-05T09:30:00Z"), ZoneOffset.UTC);
		StudentSession s = sessions.saveAndFlush(withVolume(new StudentSession(course, "비번", "tok-keep",
				"pw-keep-24-chars-ABCDEF")));
		sessions.updateContainerCreated(s.getId(), "old-password", 40800, IdeAuthMode.PASSWORD);
		FakeDocker docker = new FakeDocker();
		docker.running = true;

		EnsureResult r = new ContainerEnsurer(sessions, docker, new ContainerSpecs(props, IdeAuthMode.PROXY), clock)
				.ensureRunning(sessions.findById(s.getId()).orElseThrow());

		assertThat(r.outcome()).isEqualTo(EnsureOutcome.ALREADY_RUNNING);
		assertThat(docker.removed).isEmpty();
		assertThat(docker.specs).isEmpty();
		assertThat(sessions.findById(s.getId()).orElseThrow().getIdeAuthMode()).isEqualTo(IdeAuthMode.PASSWORD);
	}

	// 볼륨 이름을 모르면 다시 만들 수 없다. 학생 코드가 든 컨테이너를 지우지 않고 실패로 둔다.
	@Test
	void proxyContainerWithoutVolumeIsLeftAlone() {
		Clock clock = Clock.fixed(Instant.parse("2026-11-05T09:30:00Z"), ZoneOffset.UTC);
		StudentSession s = sessions.saveAndFlush(new StudentSession(course, "볼륨없음", "tok-novol", "pw-novol"));
		sessions.updateContainerCreated(s.getId(), "old-novol", 40900, IdeAuthMode.PROXY);
		FakeDocker docker = new FakeDocker();

		EnsureResult r = new ContainerEnsurer(sessions, docker, specs, clock)
				.ensureRunning(sessions.findById(s.getId()).orElseThrow());

		assertThat(r.outcome()).isEqualTo(EnsureOutcome.FAILED);
		assertThat(docker.removed).isEmpty();
	}

	// 한 포트의 주인은 하나다. 포트를 기록할 때 같은 포트의 옛 기록을 지우고, 멈추면 자기 포트를 지운다.
	@Test
	void eachHostPortHasOneOwner() {
		StudentSession a = sessions.saveAndFlush(new StudentSession(course, "가", "tok-port-a", "pw-a"));
		StudentSession b = sessions.saveAndFlush(new StudentSession(course, "나", "tok-port-b", "pw-b"));
		sessions.updateContainerCreated(a.getId(), "cid-a", 41500, IdeAuthMode.PROXY);
		sessions.updateContainerCreated(a.getId(), "cid-a", 41500, IdeAuthMode.PROXY);
		assertThat(sessions.findById(a.getId()).orElseThrow().getHostPort()).as("자기 포트는 그대로").isEqualTo(41500);

		sessions.updateContainerCreated(b.getId(), "cid-b", 41500, IdeAuthMode.PROXY);
		assertThat(sessions.findById(a.getId()).orElseThrow().getHostPort()).isNull();
		assertThat(sessions.findById(b.getId()).orElseThrow().getHostPort()).isEqualTo(41500);

		sessions.updateContainerState(a.getId(), 41500, true);
		assertThat(sessions.findById(a.getId()).orElseThrow().getHostPort()).isEqualTo(41500);
		assertThat(sessions.findById(b.getId()).orElseThrow().getHostPort()).isNull();

		sessions.markStopped(a.getId());
		StudentSession stopped = sessions.findById(a.getId()).orElseThrow();
		assertThat(stopped.getHostPort()).isNull();
		assertThat(stopped.isContainerRunning()).isFalse();
	}

	private static StudentSession withVolume(StudentSession s) {
		s.setVolumeName(VOLUME + "-" + s.getToken());
		return s;
	}

	// 컨테이너를 기록할 때 그 컨테이너를 만든 방식도 남긴다. 학생 화면이 비밀번호를 보일지 이것으로 정한다.
	@Test
	void joinRecordsTheModeTheContainerWasMadeWith() {
		FakeDocker docker = new FakeDocker();
		Clock clock = Clock.fixed(Instant.parse("2026-11-05T09:30:00Z"), ZoneOffset.UTC);
		ContainerSpecs proxy = new ContainerSpecs(props, IdeAuthMode.PROXY);
		SessionService service = new SessionService(sessions, courses, docker, props, (port, t) -> true,
				JsonMapper.builder().build(), proxy, new ContainerEnsurer(sessions, docker, proxy, clock),
				new PinPolicy(clock));

		StudentSession s = service.join("SPC001", "프록시학생", "1234").session();

		assertThat(s.getIdeAuthMode()).isEqualTo(IdeAuthMode.PROXY);
		assertThat(docker.specs).hasSize(1);
		assertThat(docker.specs.get(0).cmd()).isEqualTo(ContainerSpecs.PROXY_CMD);

		StudentSession p = SessionServices.create(sessions, courses, docker, props, (port, t) -> true,
				JsonMapper.builder().build()).join("SPC001", "비번학생", "1234").session();
		assertThat(p.getIdeAuthMode()).isEqualTo(IdeAuthMode.PASSWORD);
		assertThat(docker.specs.get(1).cmd()).isNull();
	}

	// 사라진 컨테이너를 다시 만들 때는 지금 설정을 따르고 기록도 바꾼다. 같은 설정에서 멈춘 컨테이너를 다시 켜는 것은
	// 처음 실행 인자 그대로이므로 기록도 그대로다(설정이 password로 바뀐 경우는 proxyContainerIsReplaced… 참고).
	@Test
	void recreateFollowsTheCurrentSettingButRestartKeepsTheRecordedMode() {
		Clock clock = Clock.fixed(Instant.parse("2026-11-05T09:30:00Z"), ZoneOffset.UTC);
		StudentSession s = keyed(KEY);
		s.setContainerId("gone-container");
		s = sessions.saveAndFlush(s);
		sessions.updateContainerCreated(s.getId(), "gone-container", 40000, IdeAuthMode.PASSWORD);

		FakeDocker docker = new FakeDocker();
		docker.running = false;
		docker.containerExistsAnswer = false;
		ContainerSpecs proxy = new ContainerSpecs(props, IdeAuthMode.PROXY);
		EnsureResult r = new ContainerEnsurer(sessions, docker, proxy, clock)
				.ensureRunning(sessions.findById(s.getId()).orElseThrow());

		assertThat(r.outcome()).isEqualTo(EnsureOutcome.RECREATED);
		assertThat(docker.specs.get(0).cmd()).isEqualTo(ContainerSpecs.PROXY_CMD);
		StudentSession recreated = sessions.findById(s.getId()).orElseThrow();
		assertThat(recreated.getIdeAuthMode()).isEqualTo(IdeAuthMode.PROXY);

		FakeDocker existing = new FakeDocker();
		existing.running = false;
		existing.containerExistsAnswer = true;
		existing.ports.put(recreated.getContainerId(), 40500);
		EnsureResult restarted = new ContainerEnsurer(sessions, existing, proxy, clock).ensureRunning(recreated);

		assertThat(restarted.outcome()).isEqualTo(EnsureOutcome.STARTED);
		assertThat(existing.specs).isEmpty();
		assertThat(sessions.findById(s.getId()).orElseThrow().getIdeAuthMode()).isEqualTo(IdeAuthMode.PROXY);
	}
}
