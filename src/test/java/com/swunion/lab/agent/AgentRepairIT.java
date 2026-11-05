package com.swunion.lab.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.swunion.lab.check.CheckResult;
import com.swunion.lab.check.EnvironmentChecker;
import com.swunion.lab.config.ApiKeyResolver;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseService;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.docker.ExecResult;
import com.swunion.lab.session.EnvStatus;
import com.swunion.lab.session.SessionRepository;
import com.swunion.lab.session.SessionService;
import com.swunion.lab.session.StudentSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 실제 Docker + 실제 LLM. ./gradlew llmTest 에서만 돈다. API 키는 ApiKeyResolver가 런타임에 .env/환경변수에서
 * 읽는다(출력하지 않음). 주기 점검 스케줄러는 꺼져 있고, 점검과 에이전트를 테스트가 직접 동기로 부른다.
 */
@Tag("integration")
@Tag("llm")
@SpringBootTest(properties = {
		"spring.config.import=optional:file:./build/no-such.env[.properties]",
		"spring.datasource.url=jdbc:h2:mem:agentrepairit",
		// 실서버 이미지·학생 볼륨과 겹치지 않는 테스트 전용 접두사 (JoinIT와 같은 이유)
		"lab.course-image-prefix=" + AgentRepairIT.IMAGE_PREFIX,
		"lab.container-name-prefix=" + AgentRepairIT.NAME_PREFIX
})
class AgentRepairIT {

	private static final Logger log = LoggerFactory.getLogger(AgentRepairIT.class);
	static final String IMAGE_PREFIX = "swunion-it/agent-course-";
	static final String NAME_PREFIX = "swunion-it-agt";
	static final Path DATA_DIR;

	static {
		try {
			DATA_DIR = Files.createTempDirectory("swunion-it-agent-data");
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("lab.data-dir", DATA_DIR::toString);
	}

	@Autowired
	CourseService courseService;
	@Autowired
	SessionService sessionService;
	@Autowired
	SessionRepository sessions;
	@Autowired
	DockerGateway docker;
	@Autowired
	DockerClient client;
	@Autowired
	EnvironmentChecker checker;
	@Autowired
	AgentRunner runner;

	@Test
	void repairsMissingPackageAndEscalatesWhenVenvIsReadOnly() throws Exception {
		assertThat(new ApiKeyResolver().resolve()).as("API 키(.env 또는 환경변수)").isPresent();
		for (int i = 1; i <= 3; i++) {
			removeImageQuietly(IMAGE_PREFIX + i + ":1");
		}
		Course created = courseService.create("에이전트 통합 테스트", "3.12", "six==1.16.0");
		long courseId = created.getId();
		String tag = IMAGE_PREFIX + courseId + ":1";
		List<StudentSession> joined = new ArrayList<>();
		try {
			Course c = null;
			for (int i = 0; i < 300; i++) {
				c = courseService.get(courseId);
				if (c.getStatus() == CourseStatus.READY || c.getStatus() == CourseStatus.FAILED) {
					break;
				}
				Thread.sleep(1000);
			}
			assertThat(c.getStatus()).as("오류: %s", c.getErrorSummary()).isEqualTo(CourseStatus.READY);

			// 시나리오 A: 학생이 패키지를 지움 → 점검 불일치 → 에이전트 복구 → 서버 재점검 OK
			StudentSession a = sessionService.join(c.getEntryCode(), "학생A", "1234").session();
			joined.add(a);
			exec(a, List.of("uv", "pip", "uninstall", "--python", "/opt/venv/bin/python", "six"));
			CheckResult broken = checker.checkAndRecord(a);
			assertThat(broken.signature()).isEqualTo("MISSING:six");

			AgentAction actionA = runner.run(new AgentContext(a, Trigger.CHECK, broken, null, null, null));
			logAction("A", actionA);
			assertThat(actionA.getOutcome()).isEqualTo(Outcome.RESOLVED);
			assertThat(actionA.isRecheckOk()).isTrue();
			assertThat(actionA.getToolLogJson()).contains("restore_packages");
			assertThat(checker.check(a).ok()).isTrue();
			assertThat(docker.exec(a.getContainerId(), List.of("/opt/venv/bin/python", "-c", "import six"),
					Duration.ofSeconds(20)).exitCode()).as("six import").isZero();
			StudentSession freshA = sessions.findById(a.getId()).orElseThrow();
			assertThat(freshA.isNeedsHelp()).isFalse();
			assertThat(freshA.getEnvStatus()).isEqualTo(EnvStatus.OK);

			// 시나리오 B: 패키지를 지우고 가상환경 쓰기 권한을 없앰 → coder 복구가 실패 → 서버가 강사에게 넘김
			StudentSession b = sessionService.join(c.getEntryCode(), "학생B", "1234").session();
			joined.add(b);
			exec(b, List.of("uv", "pip", "uninstall", "--python", "/opt/venv/bin/python", "six"));
			exec(b, List.of("chmod", "-R", "a-w", "/opt/venv"));
			CheckResult brokenB = checker.checkAndRecord(b);
			assertThat(brokenB.signature()).isEqualTo("MISSING:six");

			AgentAction actionB = runner.run(new AgentContext(b, Trigger.CHECK, brokenB, null, null, null));
			logAction("B", actionB);
			assertThat(actionB.getOutcome()).isEqualTo(Outcome.ESCALATED);
			assertThat(actionB.isRecheckOk()).isFalse();
			StudentSession freshB = sessions.findById(b.getId()).orElseThrow();
			assertThat(freshB.isNeedsHelp()).isTrue();
			assertThat(freshB.getNeedsHelpReason()).contains("재점검 실패");
			assertThat(freshB.getLastEscalatedSignature()).isEqualTo("MISSING:six");
			assertThat(freshB.getEnvStatus()).isEqualTo(EnvStatus.MISMATCH);
		}
		finally {
			for (StudentSession s : joined) {
				try {
					docker.remove(s.getContainerId());
				}
				catch (RuntimeException ignored) {
				}
				try {
					if (s.getVolumeName() != null && s.getVolumeName().startsWith(NAME_PREFIX)) {
						docker.removeVolume(s.getVolumeName());
					}
				}
				catch (RuntimeException ignored) {
				}
			}
			removeImageQuietly(tag);
		}
	}

	private void logAction(String scenario, AgentAction a) {
		log.info("시나리오 {} AgentAction: outcome={} classification={} recheckOk={} recheckIssues={} summary={}"
				+ " escalationReason={}\n  toolLog={}\n  rejected={}", scenario, a.getOutcome(), a.getClassification(),
				a.isRecheckOk(), a.getRecheckIssuesJson(), a.getSummary(), a.getEscalationReason(), a.getToolLogJson(),
				a.getRejectedJson());
	}

	private void exec(StudentSession s, List<String> cmd) {
		ExecResult r = docker.exec(s.getContainerId(), cmd, Duration.ofSeconds(60));
		assertThat(r.exitCode()).as(r.stderr()).isZero();
	}

	private void removeImageQuietly(String tag) {
		try {
			client.removeImageCmd(tag).withForce(true).exec();
		}
		catch (RuntimeException ignored) {
		}
	}
}
