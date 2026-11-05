package com.swunion.lab.ide;

import static com.swunion.lab.session.StudentRequests.cookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.session.EnvStatus;
import com.swunion.lab.session.IdeAuthMode;
import com.swunion.lab.session.SessionRepository;
import com.swunion.lab.session.StudentSession;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Caddy forward_auth가 /ide/{port}/ 요청마다 부르는 확인. 주인 학생의 쿠키만 204이고 나머지는 모두 401이다. */
@SpringBootTest(properties = {
		"spring.config.import=optional:file:./build/no-such.env[.properties]",
		"spring.datasource.url=jdbc:h2:mem:ideauthctrl"
})
@AutoConfigureMockMvc
class IdeAuthControllerTest {

	static final String DENIED = "이 IDE를 열 수 없습니다. 코딩책상에 입장한 브라우저에서 열어 주세요.";

	@Autowired
	MockMvc mvc;
	@Autowired
	CourseRepository courses;
	@Autowired
	SessionRepository sessions;
	@Autowired
	JdbcTemplate jdbc;
	@MockitoBean
	DockerGateway docker;

	Course course;
	StudentSession owner;
	StudentSession other;
	String ownerToken;
	String otherToken;

	@BeforeEach
	void setUp() {
		Course c = new Course("확인", "3.12", "six==1.16.0");
		c.setStatus(CourseStatus.READY);
		course = courses.save(c);
		ownerToken = String.format("%064x", System.nanoTime());
		otherToken = String.format("%064x", System.nanoTime() + 1);
		owner = running("주인", ownerToken, 41001);
		other = running("남", otherToken, 41002);
	}

	/** join이 컨테이너를 기록하는 것과 같은 길(포트 주인 정리 포함)로 돌고 있는 세션을 만든다. */
	private StudentSession running(String name, String token, int port) {
		StudentSession s = sessions.save(new StudentSession(course, name, token, "pw-" + name));
		sessions.updateContainerCreated(s.getId(), "cid-" + token.substring(48), port, IdeAuthMode.PROXY);
		return s;
	}

	@Test
	void ownerIs204() throws Exception {
		mvc.perform(get("/api/ide-auth/41001").cookie(cookie(ownerToken))).andExpect(status().isNoContent());
		mvc.perform(get("/api/ide-auth/41002").cookie(cookie(otherToken))).andExpect(status().isNoContent());
	}

	// 로그인한 다른 학생이 남의 IDE 주소를 열어도 막힌다.
	@Test
	void otherStudentIs401() throws Exception {
		expectDenied(mvc.perform(get("/api/ide-auth/41001").cookie(cookie(otherToken))));
		expectDenied(mvc.perform(get("/api/ide-auth/41002").cookie(cookie(ownerToken))));
	}

	// 공유된 IDE 주소를 쿠키 없는 브라우저에서 열거나, 재입장 전 옛 쿠키로 열면 막힌다.
	@Test
	void noCookieOrStaleCookieIs401() throws Exception {
		expectDenied(mvc.perform(get("/api/ide-auth/41001")));
		expectDenied(mvc.perform(get("/api/ide-auth/41001").cookie(cookie(""))));
		expectDenied(mvc.perform(get("/api/ide-auth/41001").cookie(cookie("deadbeef"))));

		String rotated = String.format("%064x", System.nanoTime() + 7);
		sessions.rotateToken(owner.getId(), rotated);

		expectDenied(mvc.perform(get("/api/ide-auth/41001").cookie(cookie(ownerToken))));
		mvc.perform(get("/api/ide-auth/41001").cookie(cookie(rotated))).andExpect(status().isNoContent());
	}

	@Test
	void oddPortValuesAre401() throws Exception {
		// 같은 수라도 앞에 0을 붙인 표기는 받지 않는다. 학생 화면의 IDE 주소는 늘 그냥 숫자다.
		for (String port : new String[] { "abc", "41001x", "-41001", "+41001", "041001", "0", "123456", "4100%201",
				"99999999999", "4.1e4" }) {
			expectDenied(mvc.perform(get(URI.create("http://localhost/api/ide-auth/" + port))
					.cookie(cookie(ownerToken))));
		}
	}

	// Docker는 풀린 포트를 다른 컨테이너에 다시 준다. 옛 주인의 쿠키로 새 주인의 IDE(--auth none)를 열면 안 된다.
	@Test
	void stoppedOwnerLosesThePortWhenAnotherStudentGetsIt() throws Exception {
		sessions.markStopped(owner.getId());
		expectDenied(mvc.perform(get("/api/ide-auth/41001").cookie(cookie(ownerToken))));

		String newcomer = String.format("%064x", System.nanoTime() + 11);
		running("새주인", newcomer, 41001);

		expectDenied(mvc.perform(get("/api/ide-auth/41001").cookie(cookie(ownerToken))));
		mvc.perform(get("/api/ide-auth/41001").cookie(cookie(newcomer))).andExpect(status().isNoContent());
	}

	// 서버·dockerd 재시작이나 OOM처럼 멈춤을 기록하지 못한 채 포트가 넘어가도, 새 주인을 기록할 때 옛 기록을 지운다.
	@Test
	void unrecordedStopStillLeavesOneOwnerPerPort() throws Exception {
		String newcomer = String.format("%064x", System.nanoTime() + 13);
		running("새주인", newcomer, 41001);

		assertThat(sessions.findById(owner.getId()).orElseThrow().getHostPort()).isNull();
		expectDenied(mvc.perform(get("/api/ide-auth/41001").cookie(cookie(ownerToken))));
		mvc.perform(get("/api/ide-auth/41001").cookie(cookie(newcomer))).andExpect(status().isNoContent());

		// 다시 켜며 포트가 바뀌는 길(updateContainerState)도 같다. 옛 주인이 남의 포트를 받아 오면 남이 잃는다.
		sessions.updateContainerState(owner.getId(), 41002, true);
		mvc.perform(get("/api/ide-auth/41002").cookie(cookie(ownerToken))).andExpect(status().isNoContent());
		expectDenied(mvc.perform(get("/api/ide-auth/41002").cookie(cookie(otherToken))));
	}

	// 포트 기록이 맞아도 컨테이너가 멈췄다고 점검이 기록했거나 수업이 끝났으면 막는다.
	@Test
	void notRunningOrEndedCourseIs401() throws Exception {
		sessions.updateCheckState(owner.getId(), false, false, EnvStatus.UNKNOWN, "[]", "[]");
		expectDenied(mvc.perform(get("/api/ide-auth/41001").cookie(cookie(ownerToken))));

		mvc.perform(get("/api/ide-auth/41002").cookie(cookie(otherToken))).andExpect(status().isNoContent());
		course.setStatus(CourseStatus.ENDED);
		courses.save(course);
		expectDenied(mvc.perform(get("/api/ide-auth/41002").cookie(cookie(otherToken))));
	}

	// 학생 쿠키 전에 발급한 토큰(주소로 나간 토큰)은 쿠키에 넣어도 IDE를 열지 못한다.
	@Test
	void tokenIssuedBeforeCookiesIs401() throws Exception {
		jdbc.update("update student_session set token_issued_at = null where id = ?", owner.getId());

		expectDenied(mvc.perform(get("/api/ide-auth/41001").cookie(cookie(ownerToken))));
	}

	@Test
	void sessionWithoutPortIs401() throws Exception {
		Course c = courses.save(new Course("포트 없음", "3.12", "six==1.16.0"));
		String token = String.format("%064x", System.nanoTime() + 3);
		sessions.save(new StudentSession(c, "포트없음", token, "pw"));

		expectDenied(mvc.perform(get("/api/ide-auth/0").cookie(cookie(token))));
		expectDenied(mvc.perform(get("/api/ide-auth/41001").cookie(cookie(token))));
	}

	// 로그인 없이 열려 있고(Caddy가 부른다) GET이라 CSRF와 무관하다. 응답이 세션을 만들거나 학생 쿠키를 건드리지 않는다.
	@Test
	void openWithoutLoginAndLeavesCookiesAlone() throws Exception {
		mvc.perform(get("/api/ide-auth/41001").cookie(cookie(ownerToken)))
				.andExpect(status().isNoContent())
				.andExpect(r -> assertThat(r.getRequest().getSession(false)).isNull())
				.andExpect(r -> assertThat(r.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
						.noneMatch(h -> h.startsWith("LAB_STUDENT=")));
	}

	private static void expectDenied(ResultActions result) throws Exception {
		result.andExpect(status().isUnauthorized())
				.andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
				.andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain;charset=UTF-8"))
				.andExpect(content().string(DENIED));
	}
}
