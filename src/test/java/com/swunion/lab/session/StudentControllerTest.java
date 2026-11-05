package com.swunion.lab.session;

import static com.swunion.lab.session.StudentRequests.cookie;
import static com.swunion.lab.session.StudentRequests.xsrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.swunion.lab.agent.Classification;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseProvisioner;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.help.HelpRequest;
import com.swunion.lab.help.HelpRequestRepository;
import com.swunion.lab.help.HelpStatus;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
		"spring.config.import=optional:file:./build/no-such.env[.properties]",
		"spring.datasource.url=jdbc:h2:mem:studentctrl"
})
@AutoConfigureMockMvc
class StudentControllerTest {

	static final String NOT_FOUND = "입장 정보를 찾을 수 없습니다. 다시 입장해 주세요.";

	@Autowired
	MockMvc mvc;
	@Autowired
	CourseRepository courses;
	@Autowired
	SessionRepository sessions;
	@Autowired
	HelpRequestRepository helps;
	@Autowired
	JsonMapper json;
	@Autowired
	JdbcTemplate jdbc;
	@MockitoBean
	DockerGateway docker;
	@MockitoBean
	HealthWaiter health;
	@MockitoBean
	CourseProvisioner provisioner;

	@Test
	void joinGivesHttpOnlyStrictCookieAndNoTokenInBody() throws Exception {
		readyCourse("CTRL01", "x/course-1:1");
		when(docker.createAndStart(any())).thenReturn("cid-secret");
		when(docker.hostPort("cid-secret", 8080)).thenReturn(40123);
		when(health.awaitHealthy(anyInt(), any())).thenReturn(true);

		MvcResult joined = join("CTRL01", "철수", "1234").andExpect(status().isCreated())
				.andExpect(jsonPath("$.resumed").value(false))
				.andExpect(jsonPath("$.token").doesNotExist())
				.andReturn();
		String token = StudentRequests.token(joined);
		String body = joined.getResponse().getContentAsString();
		assertThat(json.readValue(body, new TypeReference<Map<String, Object>>() {
		}).keySet()).containsExactly("resumed");
		assertThat(body).doesNotContain(token);
		assertThat(sessions.findByToken(token)).isPresent();

		// http 요청이므로 Secure가 없다. 12시간, 경로 /, 스크립트가 못 읽고, 다른 사이트에서 시작한 요청에는 실리지 않는다.
		String header = StudentRequests.setCookie(joined);
		assertThat(header).contains("Path=/", "Max-Age=43200", "HttpOnly", "SameSite=Strict").doesNotContain("Secure");

		mvc.perform(get("/api/me").cookie(cookie(token))).andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("철수"))
				.andExpect(jsonPath("$.courseName").value("파이썬 기초"))
				.andExpect(jsonPath("$.ideUrl").value("http://localhost:40123/"))
				.andExpect(jsonPath("$.idePassword").isString())
				.andExpect(jsonPath("$.envStatus").value("UNKNOWN"))
				.andExpect(jsonPath("$.issues").isEmpty())
				.andExpect(jsonPath("$.needsHelp").value(false))
				.andExpect(jsonPath("$.workspaceLost").value(false))
				.andExpect(jsonPath("$.latestHelp").doesNotExist())
				.andExpect(jsonPath("$.containerId").doesNotExist())
				.andExpect(jsonPath("$.token").doesNotExist());
	}

	// Caddy 뒤에서는 RemoteIpValve가 X-Forwarded-Proto: https를 보고 요청을 secure로 만든다(MockMvc는 그 결과만 흉내 낸다).
	@Test
	void httpsJoinMarksTheCookieSecure() throws Exception {
		readyCourse("CTRL10", "x/course-10:1");
		when(docker.createAndStart(any())).thenReturn("cid-https");
		when(docker.hostPort("cid-https", 8080)).thenReturn(40124);
		when(health.awaitHealthy(anyInt(), any())).thenReturn(true);

		MvcResult joined = mvc.perform(post("/api/join").secure(true).contentType(MediaType.APPLICATION_JSON)
				.content("{\"entryCode\":\"CTRL10\",\"name\":\"보안\",\"pin\":\"1234\"}"))
				.andExpect(status().isCreated()).andReturn();

		assertThat(StudentRequests.setCookie(joined)).contains("; Secure", "HttpOnly", "SameSite=Strict");
	}

	@Test
	void meWithoutOrWithBadCookieIs404WithRejoinMessage() throws Exception {
		mvc.perform(get("/api/me")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value(NOT_FOUND));
		mvc.perform(get("/api/me").cookie(cookie("deadbeef"))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value(NOT_FOUND));
		mvc.perform(get("/api/me").cookie(cookie(""))).andExpect(status().isNotFound());
		// 주소의 토큰은 더 이상 아무것도 열지 않는다(?t=로 열린 예전 화면).
		Course c = readyCourse("CTRL11", "x/course-11:1");
		sessions.save(new StudentSession(c, "옛주소", "tok-old-url-style", "pw"));
		mvc.perform(get("/api/me").param("t", "tok-old-url-style")).andExpect(status().isNotFound());
	}

	@Test
	void studentStatusKeepsItsFieldsAndNeverShowsTheIdeKey() throws Exception {
		Course c = readyCourse("CTRL05", "x/course-5:1");
		String key = "ab".repeat(32);
		StudentSession s = sessions.save(new StudentSession(c, "민수", "민수", "tok-student-fields", "pw-fields-24-chars-AB",
				null, key));
		s.setHostPort(40125);
		sessions.save(s);

		String body = mvc.perform(get("/api/me").cookie(cookie("tok-student-fields"))).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		// 기존 아홉 필드는 그대로이고, 공통 상태 뷰의 issuesText와 courseStatus가 더해진다. 컨테이너 키와 토큰은 없다.
		Map<String, Object> fields = json.readValue(body, new TypeReference<Map<String, Object>>() {
		});
		assertThat(fields.keySet()).containsExactlyInAnyOrder("name", "courseName", "ideUrl", "idePassword",
				"envStatus", "issues", "issuesText", "needsHelp", "workspaceLost", "latestHelp", "courseStatus");
		assertThat(body).doesNotContain(key).doesNotContain("ideKey").doesNotContain("tok-student-fields");
		mvc.perform(get("/api/me").cookie(cookie("tok-student-fields")))
				.andExpect(jsonPath("$.ideUrl").value("http://localhost:40125/"))
				.andExpect(jsonPath("$.idePassword").value("pw-fields-24-chars-AB"))
				.andExpect(jsonPath("$.issuesText").isEmpty());
	}

	// proxy 방식으로 만든 컨테이너는 Caddy가 쿠키로 주인을 확인하므로 비밀번호를 화면에 내리지 않는다. 기록이 없는
	// 예전 세션(null)과 password 방식은 지금처럼 비밀번호를 준다.
	@Test
	void idePasswordOnlyForPasswordModeContainers() throws Exception {
		Course c = readyCourse("CTRL12", "x/course-12:1");
		StudentSession legacy = sessions.save(new StudentSession(c, "옛학생", "tok-mode-legacy", "pw-legacy-mode"));
		StudentSession password = sessions.save(new StudentSession(c, "비번", "tok-mode-password", "pw-password-mode"));
		StudentSession proxy = sessions.save(new StudentSession(c, "프록시", "tok-mode-proxy", "pw-proxy-mode"));
		sessions.updateContainerCreated(password.getId(), "cid-password", 40301, IdeAuthMode.PASSWORD);
		sessions.updateContainerCreated(proxy.getId(), "cid-proxy", 40302, IdeAuthMode.PROXY);
		assertThat(sessions.findById(legacy.getId()).orElseThrow().getIdeAuthMode()).isNull();

		mvc.perform(get("/api/me").cookie(cookie("tok-mode-legacy")))
				.andExpect(jsonPath("$.idePassword").value("pw-legacy-mode"));
		mvc.perform(get("/api/me").cookie(cookie("tok-mode-password")))
				.andExpect(jsonPath("$.idePassword").value("pw-password-mode"));
		String body = mvc.perform(get("/api/me").cookie(cookie("tok-mode-proxy"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.idePassword").doesNotExist())
				.andExpect(jsonPath("$.ideUrl").value("http://localhost:40302/"))
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("pw-proxy-mode");
	}

	@Test
	void statusIncludesCourseStatus() throws Exception {
		Course c = readyCourse("CTRL06", "x/course-6:1");
		sessions.save(new StudentSession(c, "하나", "tok-course-status", "pw"));

		mvc.perform(get("/api/me").cookie(cookie("tok-course-status"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.courseStatus").value("READY"));

		c.setStatus(CourseStatus.ENDED);
		courses.save(c);

		mvc.perform(get("/api/me").cookie(cookie("tok-course-status"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.courseStatus").value("ENDED"));
	}

	@Test
	void latestHelpCarriesClassification() throws Exception {
		Course c = readyCourse("CTRL07", "x/course-7:1");
		StudentSession s = sessions.save(new StudentSession(c, "두리", "tok-help-class", "pw"));
		HelpRequest h = helps.save(new HelpRequest(s, "KeyError", "KeyError: a"));

		// 처리 중에는 분류가 아직 없다. 키는 있고 값만 null이다.
		mvc.perform(get("/api/me").cookie(cookie("tok-help-class"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.latestHelp.status").value("PROCESSING"))
				.andExpect(jsonPath("$.latestHelp.classification").value(nullValue()));

		helps.updateResult(h.getId(), HelpStatus.EXPLAINED, "설명", false, Classification.CODE);

		mvc.perform(get("/api/me").cookie(cookie("tok-help-class"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.latestHelp.classification").value("CODE"));
	}

	@Test
	void statusNeverCarriesPin() throws Exception {
		Course c = readyCourse("CTRL08", "x/course-8:1");
		StudentSession s = new StudentSession(c, "세나", "tok-no-pin", "pw");
		sessions.save(s);
		sessions.setPin(s.getId(), "$2a$10$hashhashhashhashhashhashhashhashhashhashhashhashhashhash");

		String body = mvc.perform(get("/api/me").cookie(cookie("tok-no-pin"))).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		Map<String, Object> fields = json.readValue(body, new TypeReference<Map<String, Object>>() {
		});
		assertThat(fields).doesNotContainKeys("pin", "pinHash");
		assertThat(body).doesNotContain("hashhash");
	}

	@Test
	void workspaceLostIsShownOnceTheWorkspaceWasRecreatedEmpty() throws Exception {
		Course c = readyCourse("CTRL02", "x/course-2:1");
		StudentSession s = sessions.save(new StudentSession(c, "영희", "tok-ws-lost-0001", "pw"));
		s.setHostPort(40124);
		sessions.save(s);

		mvc.perform(get("/api/me").cookie(cookie("tok-ws-lost-0001"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.workspaceLost").value(false));

		sessions.markWorkspaceLost(s.getId(), Instant.parse("2026-11-05T09:30:00Z"));

		mvc.perform(get("/api/me").cookie(cookie("tok-ws-lost-0001"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.workspaceLost").value(true));
	}

	@Test
	void badCodeIs400WithoutCookie() throws Exception {
		MvcResult r = mvc.perform(post("/api/join").contentType(MediaType.APPLICATION_JSON)
				.content("{\"entryCode\":\"NOPE00\",\"name\":\"철수\",\"pin\":\"1234\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(containsString("수업 코드")))
				.andReturn();
		assertThat(StudentRequests.setCookie(r)).isNull();
	}

	// 토큰이 주소에 들어가던 학생 API는 남아 있지 않다. 컨트롤러가 받지 않고 정적 파일 처리가 404를 준다.
	@Test
	void tokenInPathApisAreGone() throws Exception {
		Course c = readyCourse("CTRL13", "x/course-13:1");
		String token = "ab".repeat(32);
		sessions.save(new StudentSession(c, "경로", token, "pw"));

		mvc.perform(get("/api/s/" + token)).andExpect(status().isNotFound())
				.andExpect(r -> assertThat(r.getResponse().getContentAsString()).doesNotContain("경로"));
		mvc.perform(get("/api/s/" + token + "/local/install").param("os", "macos")).andExpect(status().isNotFound());
		mvc.perform(post("/api/s/" + token + "/help").with(xsrf())).andExpect(status().isNotFound());
		mvc.perform(post("/api/s/" + token + "/escalate").with(xsrf())).andExpect(status().isNotFound());
		// /l/ 아래는 다운로드 코드만 받는다. 세션 토큰은 코드가 아니다.
		mvc.perform(get("/l/" + token + "/install.sh")).andExpect(status().isNotFound());
		mvc.perform(get("/l/" + token + "/code.zip")).andExpect(status().isNotFound());
		assertThat(sessions.findByToken(token).orElseThrow().isNeedsHelp()).isFalse();
	}

	@Test
	void joinRequestToStringHidesThePin() {
		// DEBUG 요청 로그가 본문 객체를 찍어도 PIN은 남지 않아야 한다.
		String printed = new StudentController.JoinRequest("CTRL09", "철수", "7391").toString();

		assertThat(printed).doesNotContain("7391").contains("CTRL09").contains("철수").contains("pin=");
	}

	@Test
	void rejoinGivesANewCookieAndTheOldBrowserMustRejoin() throws Exception {
		readyCourse("CTRL03", "x/course-3:1");
		when(docker.createAndStart(any())).thenReturn("cid-rejoin");
		when(docker.hostPort("cid-rejoin", 8080)).thenReturn(40200);
		when(docker.isRunning("cid-rejoin")).thenReturn(true);
		when(health.awaitHealthy(anyInt(), any())).thenReturn(true);

		String oldToken = StudentRequests.token(join("CTRL03", "민지", "4321").andExpect(status().isCreated())
				.andReturn());
		String newToken = StudentRequests.token(join("CTRL03", " 민지 ", "4321").andExpect(status().isOk())
				.andExpect(jsonPath("$.resumed").value(true))
				.andExpect(jsonPath("$.token").doesNotExist())
				.andReturn());

		assertThat(newToken).isNotEqualTo(oldToken);
		Cookie old = cookie(oldToken);
		mvc.perform(get("/api/me").cookie(old)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value(NOT_FOUND));
		mvc.perform(post("/api/me/help").cookie(old).with(xsrf())).andExpect(status().isNotFound());
		mvc.perform(post("/api/me/escalate").cookie(old).with(xsrf())).andExpect(status().isNotFound());
		mvc.perform(post("/api/me/local/link").param("os", "macos").cookie(old).with(xsrf()))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/me/code.zip").cookie(old)).andExpect(status().isNotFound());
		mvc.perform(get("/api/me").cookie(cookie(newToken))).andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("민지"));
	}

	@Test
	void wrongPinIs403LockedIs429AndBadFormatIs400() throws Exception {
		readyCourse("CTRL04", "x/course-4:1");
		when(docker.createAndStart(any())).thenReturn("cid-pin");
		when(docker.hostPort("cid-pin", 8080)).thenReturn(40300);
		when(health.awaitHealthy(anyInt(), any())).thenReturn(true);
		join("CTRL04", "수빈", "1111").andExpect(status().isCreated());

		join("CTRL04", "수빈", "12a4").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value(containsString("비밀번호")));
		mvc.perform(post("/api/join").contentType(MediaType.APPLICATION_JSON)
				.content("{\"entryCode\":\"CTRL04\",\"name\":\"수빈\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(containsString("비밀번호")));
		MvcResult wrong = join("CTRL04", "수빈", "0000").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value(containsString("남은 기회 4번")))
				.andExpect(jsonPath("$.token").doesNotExist())
				.andReturn();
		assertThat(StudentRequests.setCookie(wrong)).as("틀린 PIN에는 쿠키를 주지 않는다").isNull();
		for (int i = 0; i < 4; i++) {
			join("CTRL04", "수빈", "0000").andExpect(status().isForbidden());
		}
		MvcResult locked = join("CTRL04", "수빈", "1111").andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.error").value(containsString("분 뒤")))
				.andExpect(jsonPath("$.token").doesNotExist())
				.andReturn();
		assertThat(StudentRequests.setCookie(locked)).isNull();
	}

	// ---- 나가기와 CSRF ----

	// 나가기는 브라우저 쿠키를 지우고 서버 토큰도 바꾼다. 남은 쿠키 값과 화면에 보였던 설치 명령이 함께 끊긴다.
	@Test
	void logoutClearsTheCookieRotatesTheTokenAndNeedsCsrf() throws Exception {
		Course c = readyCourse("CTRL14", "x/course-14:1");
		StudentSession s = sessions.save(new StudentSession(c, "나감", "tok-logout", "pw"));
		Cookie student = cookie("tok-logout");
		String installUrl = json.readValue(mvc.perform(post("/api/me/local/link").param("os", "macos").cookie(student)
				.with(xsrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				new TypeReference<Map<String, String>>() {
				}).get("scriptUrl").replace("http://localhost:8080", "");
		mvc.perform(get(installUrl)).andExpect(status().isOk());

		// 다른 사이트가 학생을 몰래 내보내지 못한다.
		mvc.perform(post("/api/me/logout").cookie(student)).andExpect(status().isForbidden());
		mvc.perform(get("/api/me").cookie(student)).andExpect(status().isOk());

		MvcResult out = mvc.perform(post("/api/me/logout").cookie(student).with(xsrf()))
				.andExpect(status().isNoContent()).andReturn();
		assertThat(StudentRequests.setCookie(out)).startsWith("LAB_STUDENT=;")
				.contains("Max-Age=0", "Path=/", "HttpOnly", "SameSite=Strict");
		mvc.perform(get("/api/me").cookie(student)).andExpect(status().isNotFound());
		mvc.perform(get(installUrl)).andExpect(status().isNotFound());
		// 쿠키가 없거나 무효여도 나가기는 204다.
		mvc.perform(post("/api/me/logout").with(xsrf())).andExpect(status().isNoContent());
		mvc.perform(post("/api/me/logout").cookie(student).with(xsrf())).andExpect(status().isNoContent());
		// 세션 자체는 남는다(토큰만 바뀌었다). 같은 이름과 PIN으로 다시 들어올 수 있다.
		StudentSession after = sessions.findById(s.getId()).orElseThrow();
		assertThat(after.getToken()).matches("[0-9a-f]{64}").isNotEqualTo("tok-logout");
		assertThat(after.getTokenIssuedAt()).isNotNull();
	}

	// 학생 쿠키 전에 발급한 토큰은 /lab?t=, /l/<토큰>/ 주소로 나갔다. 쿠키에 넣어도 받지 않고, PIN 재입장으로 새 토큰을 준다.
	@Test
	void tokensIssuedBeforeCookiesMustRejoin() throws Exception {
		readyCourse("CTRL18", "x/course-18:1");
		when(docker.createAndStart(any())).thenReturn("cid-legacy-token");
		when(docker.hostPort("cid-legacy-token", 8080)).thenReturn(40500);
		when(docker.isRunning("cid-legacy-token")).thenReturn(true);
		when(health.awaitHealthy(anyInt(), any())).thenReturn(true);
		String old = StudentRequests.token(join("CTRL18", "옛토큰", "2468").andExpect(status().isCreated()).andReturn());
		long id = sessions.findByToken(old).orElseThrow().getId();
		// 배포 전 DB의 행처럼 만든다. 새 열이 생기며 기존 행은 비어 있다.
		jdbc.update("update student_session set token_issued_at = null where id = ?", id);

		mvc.perform(get("/api/me").cookie(cookie(old))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value(NOT_FOUND));
		mvc.perform(post("/api/me/escalate").cookie(cookie(old)).with(xsrf())).andExpect(status().isNotFound());
		mvc.perform(post("/api/me/local/link").param("os", "macos").cookie(cookie(old)).with(xsrf()))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/me/code.zip").cookie(cookie(old))).andExpect(status().isNotFound());

		String fresh = StudentRequests.token(join("CTRL18", "옛토큰", "2468").andExpect(status().isOk()).andReturn());
		assertThat(fresh).isNotEqualTo(old);
		assertThat(sessions.findById(id).orElseThrow().getTokenIssuedAt()).isNotNull();
		mvc.perform(get("/api/me").cookie(cookie(fresh))).andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("옛토큰"));
	}

	// 포트 기록이 없으면(만드는 중, 수업 종료로 멈춤) IDE 주소를 주지 않는다. 화면은 IDE 대신 안내를 보인다.
	@Test
	void ideUrlIsNullWithoutAPort() throws Exception {
		Course c = readyCourse("CTRL19", "x/course-19:1");
		StudentSession s = sessions.save(new StudentSession(c, "포트", "tok-no-port", "pw"));
		mvc.perform(get("/api/me").cookie(cookie("tok-no-port"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.ideUrl").value(nullValue()));

		sessions.updateContainerCreated(s.getId(), "cid-port", 40601, IdeAuthMode.PASSWORD);
		mvc.perform(get("/api/me").cookie(cookie("tok-no-port")))
				.andExpect(jsonPath("$.ideUrl").value("http://localhost:40601/"));

		sessions.markStopped(s.getId());
		mvc.perform(get("/api/me").cookie(cookie("tok-no-port")))
				.andExpect(jsonPath("$.ideUrl").value(nullValue()));
	}

	// 입장이 CSRF 검사에서 빠진 것은 JSON 본문만 받기 때문이다. 다른 사이트의 폼이 보낼 수 있는 형식(text/plain,
	// 폼, multipart)은 415이고 쿠키도 세션도 생기지 않는다. 그래서 남의 브라우저를 공격자 세션으로 입장시키지 못한다.
	@Test
	void joinAcceptsOnlyJson() throws Exception {
		Course c = readyCourse("CTRL17", "x/course-17:1");
		String body = "{\"entryCode\":\"CTRL17\",\"name\":\"폼\",\"pin\":\"1234\"}";
		for (MediaType type : new MediaType[] { MediaType.TEXT_PLAIN, MediaType.APPLICATION_FORM_URLENCODED,
				MediaType.parseMediaType("multipart/form-data; boundary=x") }) {
			MvcResult r = mvc.perform(post("/api/join").contentType(type).content(body))
					.andExpect(status().isUnsupportedMediaType()).andReturn();
			assertThat(StudentRequests.setCookie(r)).as(type.toString()).isNull();
		}
		MvcResult form = mvc.perform(post("/api/join").contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("entryCode", "CTRL17").param("name", "폼").param("pin", "1234"))
				.andExpect(status().isUnsupportedMediaType()).andReturn();
		assertThat(StudentRequests.setCookie(form)).isNull();
		assertThat(sessions.findByCourse_Id(c.getId())).isEmpty();
	}

	// 실제 쿠키 흐름: 화면이 처음 받는 응답이 XSRF-TOKEN 쿠키를 심고, 화면은 그 값을 X-XSRF-TOKEN 헤더로 보낸다.
	// 학생 쿠키만 실린 요청(다른 사이트의 폼 전송)은 403이다.
	@Test
	void studentPostsNeedTheCsrfHeader() throws Exception {
		Course c = readyCourse("CTRL15", "x/course-15:1");
		StudentSession s = sessions.save(new StudentSession(c, "보호", "tok-csrf", "pw"));
		Cookie student = cookie("tok-csrf");

		MvcResult first = mvc.perform(get("/api/me").cookie(student)).andExpect(status().isOk()).andReturn();
		Cookie xsrf = first.getResponse().getCookie("XSRF-TOKEN");
		assertThat(xsrf).as("XSRF-TOKEN 쿠키").isNotNull();
		assertThat(xsrf.isHttpOnly()).as("화면 스크립트가 읽어야 한다").isFalse();

		for (String path : new String[] { "/api/me/escalate", "/api/me/help", "/api/me/local/link?os=macos",
				"/api/me/local/diagnostics", "/api/me/local/verify", "/api/me/logout" }) {
			mvc.perform(post(path).cookie(student).contentType(MediaType.APPLICATION_JSON).content("{}"))
					.andExpect(status().isForbidden());
			mvc.perform(post(path).cookie(student, xsrf).contentType(MediaType.APPLICATION_JSON).content("{}"))
					.andExpect(status().isForbidden());
			mvc.perform(post(path).cookie(student, xsrf).header("X-XSRF-TOKEN", xsrf.getValue() + "x")
					.contentType(MediaType.APPLICATION_JSON).content("{}"))
					.andExpect(status().isForbidden());
		}
		assertThat(sessions.findById(s.getId()).orElseThrow().isNeedsHelp()).as("막힌 요청은 아무것도 바꾸지 않는다")
				.isFalse();

		mvc.perform(post("/api/me/escalate").cookie(student, xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
				.andExpect(status().isNoContent());
		assertThat(sessions.findById(s.getId()).orElseThrow().isNeedsHelp()).isTrue();
	}

	// 입장은 PIN이 있어야 하므로 CSRF 검사에서 뺐다. 처음 여는 화면에는 아직 XSRF-TOKEN이 없을 수 있다.
	@Test
	void joinNeedsNoCsrf() throws Exception {
		readyCourse("CTRL16", "x/course-16:1");
		when(docker.createAndStart(any())).thenReturn("cid-nocsrf");
		when(docker.hostPort("cid-nocsrf", 8080)).thenReturn(40400);
		when(health.awaitHealthy(anyInt(), any())).thenReturn(true);

		join("CTRL16", "바로", "1234").andExpect(status().isCreated());
	}

	@Test
	void unknownMeIsHandledByStudentController() throws Exception {
		// 컨트롤러가 먼저 받으므로 정적 대체가 API 404를 가로채지 않는다.
		mvc.perform(get("/api/me")).andExpect(status().isNotFound())
				.andExpect(handler().handlerType(StudentController.class));
	}

	private ResultActions join(String code, String name, String pin) throws Exception {
		return mvc.perform(post("/api/join").contentType(MediaType.APPLICATION_JSON)
				.content("{\"entryCode\":\"" + code + "\",\"name\":\"" + name + "\",\"pin\":\"" + pin + "\"}"));
	}

	private Course readyCourse(String code, String image) {
		Course c = new Course("파이썬 기초", "3.12", "six==1.16.0");
		c.setStatus(CourseStatus.READY);
		c.setEntryCode(code);
		c.setImageName(image);
		return courses.save(c);
	}
}
