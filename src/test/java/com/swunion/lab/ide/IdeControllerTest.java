package com.swunion.lab.ide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.swunion.lab.agent.AgentTrigger;
import com.swunion.lab.check.Issue;
import com.swunion.lab.check.IssueType;
import com.swunion.lab.config.LabProperties;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseProvisioner;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.dashboard.DashboardService;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.help.HelpRequest;
import com.swunion.lab.help.HelpRequestRepository;
import com.swunion.lab.session.EnvStatus;
import com.swunion.lab.session.HealthWaiter;
import com.swunion.lab.session.SessionRepository;
import com.swunion.lab.session.StudentSession;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * IDE 안 확장이 부르는 API. 컨테이너 키(X-Session-Key)로 세션을 찾고, 그 세션 자신의 상태·도움 요청·강사 호출만 다룬다.
 * 테스트는 커밋되는 인메모리 DB를 클래스 안에서 나눠 쓰므로 학생·수업마다 새 값을 만든다.
 */
@SpringBootTest(properties = {
		"spring.config.import=optional:file:./build/no-such.env[.properties]",
		"spring.datasource.url=jdbc:h2:mem:idectrl"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class IdeControllerTest {

	static final String HEADER = "X-Session-Key";
	static final String BUSY = "에이전트가 처리 중입니다. 잠시 후 다시 시도해 주세요";
	static final Map<String, Object> UNAUTHORIZED = Map.of("error", "인증 정보가 없습니다");

	// 테스트마다 새 인스턴스가 만들어지지만 DB는 클래스 안에서 나눠 쓰므로 수업 수업 코드가 겹치지 않게 센다.
	static final AtomicInteger seq = new AtomicInteger();

	final SecureRandom random = new SecureRandom();

	@Autowired
	MockMvc mvc;
	@Autowired
	CourseRepository courses;
	@Autowired
	SessionRepository sessions;
	@Autowired
	HelpRequestRepository helps;
	@Autowired
	DashboardService dashboard;
	@Autowired
	LabProperties props;
	@Autowired
	JsonMapper json;
	@MockitoBean
	DockerGateway docker;
	@MockitoBean
	HealthWaiter health;
	@MockitoBean
	CourseProvisioner provisioner;
	@MockitoBean
	AgentTrigger trigger;

	/** 저장된 학생과 그 학생의 비밀 값들. 응답에 이 값이 실리면 안 된다. */
	record Student(StudentSession session, String key, String token, String password) {
		long id() {
			return session.getId();
		}
	}

	private String hex() {
		byte[] b = new byte[32];
		random.nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

	private Course course(CourseStatus status) {
		Course c = new Course("파이썬 기초", "3.12", "six==1.16.0");
		c.setStatus(status);
		c.setEntryCode("IDE" + seq.incrementAndGet());
		c.setImageName("x/course-ide:1");
		return courses.save(c);
	}

	private Student student(Course c, String name) {
		String key = hex();
		String token = hex();
		String password = "pw-" + hex().substring(0, 20);
		StudentSession s = new StudentSession(c, name, name, token, password, null, key);
		s.setHostPort(40500 + seq.incrementAndGet());
		return new Student(sessions.save(s), key, token, password);
	}

	private Student student(String name) {
		return student(course(CourseStatus.READY), name);
	}

	private StudentSession fresh(Student s) {
		return sessions.findById(s.id()).orElseThrow();
	}

	private ResultActions readStatus(String key) throws Exception {
		return mvc.perform(get("/api/ide/status").header(HEADER, key));
	}

	private ResultActions help(String key, String body) throws Exception {
		return mvc.perform(post("/api/ide/help").header(HEADER, key).contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private ResultActions escalate(String key) throws Exception {
		return mvc.perform(post("/api/ide/escalate").header(HEADER, key));
	}

	private void agentIdle() {
		when(trigger.isRunning(anyLong())).thenReturn(false);
		when(trigger.onHelp(any(), anyLong(), any(), any())).thenReturn(true);
	}

	@Test
	void statusWithKey() throws Exception {
		Student a = student("철수");
		StudentSession row = fresh(a);
		row.setEnvStatus(EnvStatus.MISMATCH);
		row.setIssuesJson(json.writeValueAsString(List.of(new Issue(IssueType.MISSING, "six", "1.16.0", null),
				new Issue(IssueType.VERSION_MISMATCH, "rich", "13.7.0", "13.6.0"))));
		sessions.save(row);

		String body = readStatus(a.key()).andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.name").value("철수"))
				.andExpect(jsonPath("$.courseName").value("파이썬 기초"))
				.andExpect(jsonPath("$.envStatus").value("MISMATCH"))
				.andExpect(jsonPath("$.issues[0].type").value("MISSING"))
				.andExpect(jsonPath("$.issues[1].actual").value("13.6.0"))
				.andExpect(jsonPath("$.issuesText[0]").value("six 없음 (기준 1.16.0)"))
				.andExpect(jsonPath("$.issuesText[1]").value("rich 버전 다름 (13.6.0, 기준 13.7.0)"))
				.andExpect(jsonPath("$.needsHelp").value(false))
				.andExpect(jsonPath("$.workspaceLost").value(false))
				.andExpect(jsonPath("$.courseStatus").value("READY"))
				.andExpect(jsonPath("$.latestHelp").doesNotExist())
				.andReturn().getResponse().getContentAsString();

		// 필드는 정확히 이것뿐이다. IDE 주소·비밀번호·토큰·키·컨테이너 정보는 싣지 않는다.
		Map<String, Object> fields = json.readValue(body, new TypeReference<Map<String, Object>>() {
		});
		assertThat(fields.keySet()).containsExactlyInAnyOrder("name", "courseName", "envStatus", "issues", "issuesText",
				"needsHelp", "latestHelp", "workspaceLost", "courseStatus");
		assertThat(body).doesNotContain(a.key()).doesNotContain(a.token()).doesNotContain(a.password())
				.doesNotContain("ideUrl").doesNotContain("idePassword").doesNotContain("40500")
				.doesNotContain("containerId");
	}

	@Test
	void issuesTextIsTheSameSentenceTheDashboardShows() throws Exception {
		Course c = course(CourseStatus.READY);
		Student a = student(c, "철수");
		StudentSession row = fresh(a);
		row.setIssuesJson(json.writeValueAsString(List.of(new Issue(IssueType.MISSING, "six", "1.16.0", null),
				new Issue(IssueType.PYTHON_MISMATCH, "python", "3.12", "error"),
				new Issue(IssueType.IDE_DOWN, "ide", null, null))));
		sessions.save(row);

		String body = readStatus(a.key()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		List<String> onDashboard = dashboard.dashboard(c.getId()).orElseThrow().students().get(0).issues();
		List<String> onPanel = json.readTree(body).get("issuesText").valueStream().map(n -> n.asString()).toList();
		assertThat(onDashboard).containsExactly("six 없음 (기준 1.16.0)", "파이썬 실행 실패", "IDE 중단");
		assertThat(onPanel).isEqualTo(onDashboard);
	}

	@Test
	void statusShowsWorkspaceLostAndLatestHelp() throws Exception {
		Student a = student("철수");
		sessions.markWorkspaceLost(a.id(), Instant.parse("2026-11-05T09:30:00Z"));
		agentIdle();
		help(a.key(), "{\"errorText\":\"KeyError: 'a'\"}").andExpect(status().isAccepted());

		readStatus(a.key()).andExpect(status().isOk())
				.andExpect(jsonPath("$.workspaceLost").value(true))
				.andExpect(jsonPath("$.latestHelp.status").value("PROCESSING"))
				.andExpect(jsonPath("$.latestHelp.errorType").value("KeyError"))
				.andExpect(jsonPath("$.latestHelp.escalated").value(false))
				.andExpect(jsonPath("$.latestHelp.errorText").doesNotExist());
	}

	@Test
	void noKeyAndWrongKeyAre401() throws Exception {
		Student a = student("철수");

		mvc.perform(get("/api/ide/status")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("인증 정보가 없습니다"));
		// 빈 값이나 공백뿐인 값은 키가 아니다. 키 없는 예전 세션(ideKey null)이 걸리지 않아야 한다.
		sessions.save(new StudentSession(course(CourseStatus.READY), "키 없음", "legacy-" + hex().substring(0, 20), "pw"));
		readStatus("  ").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("인증 정보가 없습니다"));
		readStatus("").andExpect(status().isUnauthorized());
		readStatus(hex()).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("인증 정보가 없습니다"));
		readStatus("null").andExpect(status().isUnauthorized());
		// 학생 토큰, IDE 비밀번호는 키가 아니다.
		readStatus(a.token()).andExpect(status().isUnauthorized());
		readStatus(a.password()).andExpect(status().isUnauthorized());
		// 대소문자만 다른 값도 다른 값이다.
		readStatus(a.key().toUpperCase()).andExpect(status().isUnauthorized());

		mvc.perform(post("/api/ide/help").contentType(MediaType.APPLICATION_JSON).content("{\"errorText\":\"x\"}"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("인증 정보가 없습니다"));
		help(hex(), "{\"errorText\":\"x\"}").andExpect(status().isUnauthorized());
		mvc.perform(post("/api/ide/escalate")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("인증 정보가 없습니다"));
		escalate(hex()).andExpect(status().isUnauthorized());

		// 거절된 요청은 아무것도 바꾸지 않는다.
		verify(trigger, never()).onHelp(any(), anyLong(), any(), any());
		assertThat(fresh(a).isNeedsHelp()).isFalse();
		assertThat(helps.findFirstBySessionIdOrderByIdDesc(a.id())).isEmpty();
	}

	@Test
	void instructorCredentialsAreNotAKeyAndNoCredentialsAreNeededWithOne() throws Exception {
		Student a = student("철수");

		// 컨테이너는 강사 계정 없이 부른다. 강사 계정이 있어도 키가 없으면 열리지 않는다.
		readStatus(a.key()).andExpect(status().isOk());
		mvc.perform(get("/api/ide/status").with(user(props.instructorUsername())))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("인증 정보가 없습니다"));
	}

	@Test
	void otherSessionKeyOnlyReachesItsOwnSession() throws Exception {
		Course c = course(CourseStatus.READY);
		Student a = student(c, "학생A");
		Student b = student(c, "학생B");
		StudentSession rowA = fresh(a);
		rowA.setEnvStatus(EnvStatus.MISMATCH);
		rowA.setNeedsHelp(true);
		sessions.save(rowA);
		agentIdle();

		// B의 키로는 B의 이름과 B의 상태만 나온다.
		readStatus(b.key()).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("학생B"))
				.andExpect(jsonPath("$.envStatus").value("UNKNOWN")).andExpect(jsonPath("$.needsHelp").value(false));
		readStatus(a.key()).andExpect(jsonPath("$.name").value("학생A")).andExpect(jsonPath("$.envStatus").value("MISMATCH"))
				.andExpect(jsonPath("$.needsHelp").value(true));

		// B의 키로 한 도움 요청과 강사 호출은 B에게만 남는다.
		help(b.key(), "{\"errorText\":\"KeyError: 'a'\"}").andExpect(status().isAccepted());
		escalate(b.key()).andExpect(status().isNoContent());

		HelpRequest helpOfB = helps.findFirstBySessionIdOrderByIdDesc(b.id()).orElseThrow();
		assertThat(helpOfB.isEscalated()).isTrue();
		assertThat(helps.findFirstBySessionIdOrderByIdDesc(a.id())).isEmpty();
		assertThat(fresh(b).isNeedsHelp()).isTrue();
		assertThat(fresh(b).getNeedsHelpReason()).isEqualTo("학생 호출");
		assertThat(fresh(a).getNeedsHelpReason()).as("A는 그대로").isNull();
	}

	@Test
	void helpAccepted() throws Exception {
		Student a = student("철수");
		agentIdle();

		String body = help(a.key(), "{\"errorText\":\"KeyError: 'a'\"}").andExpect(status().isAccepted())
				.andExpect(jsonPath("$.helpId").isNumber()).andReturn().getResponse().getContentAsString();

		HelpRequest saved = helps.findFirstBySessionIdOrderByIdDesc(a.id()).orElseThrow();
		assertThat(json.readTree(body).get("helpId").asLong()).isEqualTo(saved.getId());
		assertThat(json.readTree(body).size()).isEqualTo(1);
		assertThat(saved.getErrorType()).isEqualTo("KeyError");
		assertThat(saved.getErrorText()).isEqualTo("KeyError: 'a'");
		verify(trigger).onHelp(any(), anyLong(), any(), any());
	}

	@Test
	void helpWithoutBodyIsStillAccepted() throws Exception {
		Student a = student("철수");
		agentIdle();

		mvc.perform(post("/api/ide/help").header(HEADER, a.key())).andExpect(status().isAccepted());
	}

	@Test
	void helpWhileTheAgentIsBusyIs409() throws Exception {
		Student a = student("철수");
		when(trigger.isRunning(a.id())).thenReturn(true);

		help(a.key(), "{\"errorText\":\"KeyError: 'a'\"}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value(BUSY));

		assertThat(helps.findFirstBySessionIdOrderByIdDesc(a.id())).isEmpty();
	}

	@Test
	void helpLosingTheStartRaceIs409AndLeavesNoRow() throws Exception {
		Student a = student("철수");
		when(trigger.isRunning(anyLong())).thenReturn(false);
		when(trigger.onHelp(any(), anyLong(), any(), any())).thenReturn(false);

		help(a.key(), "{\"errorText\":\"KeyError: 'a'\"}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value(BUSY));

		assertThat(helps.findFirstBySessionIdOrderByIdDesc(a.id())).isEmpty();
	}

	@Test
	void escalateFlagsTheStudentCall() throws Exception {
		Student a = student("철수");
		agentIdle();
		help(a.key(), "{\"errorText\":\"KeyError: 'a'\"}").andExpect(status().isAccepted());
		sessions.addNeedsHelpReason(a.id(), "재점검 실패: MISSING:six");

		escalate(a.key()).andExpect(status().isNoContent()).andExpect(content().string(""));

		assertThat(fresh(a).isNeedsHelp()).isTrue();
		assertThat(fresh(a).getNeedsHelpReason()).isEqualTo("재점검 실패: MISSING:six / 학생 호출");
		readStatus(a.key()).andExpect(jsonPath("$.needsHelp").value(true))
				.andExpect(jsonPath("$.latestHelp.escalated").value(true));
	}

	@Test
	void endedCourse() throws Exception {
		Student a = student(course(CourseStatus.ENDED), "철수");
		agentIdle();

		help(a.key(), "{\"errorText\":\"KeyError: 'a'\"}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("종료된 수업입니다"));
		escalate(a.key()).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("종료된 수업입니다"));

		// 종료 뒤의 도움 요청이 에이전트를 깨우면 멈춘 컨테이너가 되살아난다.
		verify(trigger, never()).onHelp(any(), anyLong(), any(), any());
		assertThat(fresh(a).isNeedsHelp()).isFalse();
		assertThat(helps.findFirstBySessionIdOrderByIdDesc(a.id())).isEmpty();
		// 읽기는 학생 API처럼 종료 뒤에도 된다.
		readStatus(a.key()).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("철수"));
	}

	@Test
	void keyNotLogged(CapturedOutput output) throws Exception {
		Student a = student("로그학생");
		String wrongKey = hex();
		agentIdle();
		LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
		List<String> loggers = List.of("com.swunion", "org.springframework.web", "org.springframework.security");
		loggers.forEach(name -> logging.setLogLevel(name, org.springframework.boot.logging.LogLevel.DEBUG));
		try {
			readStatus(a.key()).andExpect(status().isOk());
			help(a.key(), "{\"errorText\":\"KeyError: 'a'\"}").andExpect(status().isAccepted());
			escalate(a.key()).andExpect(status().isNoContent());
			readStatus(wrongKey).andExpect(status().isUnauthorized());
			readStatus("").andExpect(status().isUnauthorized());
		}
		finally {
			loggers.forEach(name -> logging.setLogLevel(name, null));
		}

		String log = output.getAll();
		assertThat(log).as("DEBUG 요청 로그가 실제로 찍혔다").contains("/api/ide/status");
		assertThat(log).doesNotContain(a.key()).doesNotContain(wrongKey).doesNotContain(a.token())
				.doesNotContain(a.password());
	}

	@Test
	void serverUrlForContainersDefaultsToHostDockerInternal() {
		assertThat(props.serverUrlForContainers()).isEqualTo("http://host.docker.internal:8080");
	}
}
