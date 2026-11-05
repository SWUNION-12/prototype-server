package com.swunion.lab.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.swunion.lab.config.LabProperties;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.docker.ContainerSpec;
import com.swunion.lab.session.ContainerEnsurer.EnsureOutcome;
import com.swunion.lab.session.ContainerEnsurer.EnsureResult;
import com.swunion.lab.session.SessionServiceTest.FakeDocker;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

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

	@BeforeEach
	void setUp() {
		var props = new LabProperties("i", "p", "m", 15, "http://lab.example.com:8080", "127.0.0.1", Path.of("."),
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
				"SWUNION_SERVER_URL", SERVER_URL));
	}

	@Test
	void legacyNoKey() {
		// 이 기능 전에 만든 행은 키가 없다. 키 없는 세션에 새 키를 만들어 주지 않는다.
		StudentSession s = new StudentSession(course, "영희", "tok-specs-legacy", "pw-legacy-24-chars-ABC");
		s.setVolumeName(VOLUME);

		ContainerSpec spec = specs.forSession(s, course);

		assertThat(spec.env()).doesNotContainKey("SWUNION_IDE_KEY");
		assertThat(spec.env()).isEqualTo(Map.of("PASSWORD", "pw-legacy-24-chars-ABC", "SWUNION_SERVER_URL", SERVER_URL));
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
}
