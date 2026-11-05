package com.swunion.lab.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// 실제 쿠키 흐름(XSRF-TOKEN 쿠키 → X-XSRF-TOKEN 헤더)을 그대로 쓴다. spring-security-test의 csrf()는 이 컨텍스트의
// CSRF 저장소를 세션 저장소로 바꿔 끼우므로 여기서는 쓰지 않는다. 정적 파일은 src/test/resources/static의 고정 파일이다.
@SpringBootTest(properties = {
		"spring.config.import=optional:file:./build/no-such.env[.properties]",
		"spring.datasource.url=jdbc:h2:mem:authflow",
		"lab.instructor-password=" + AuthFlowTest.PASSWORD
})
@AutoConfigureMockMvc
class AuthFlowTest {

	static final String USERNAME = "instructor";
	static final String PASSWORD = "Auth-Flow-Pw-3";
	static final String CSRF_COOKIE = "XSRF-TOKEN";
	static final String CSRF_HEADER = "X-XSRF-TOKEN";

	@Autowired
	MockMvc mvc;

	@Test
	void loginWithRightPasswordGivesSession() throws Exception {
		MvcResult login = login(PASSWORD, null);
		assertThat(login.getResponse().getStatus()).isEqualTo(204);
		MockHttpSession session = session(login);

		mvc.perform(get("/api/courses").session(session)).andExpect(status().isOk());
		mvc.perform(get("/api/auth/me").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value(USERNAME));
	}

	@Test
	void loginWithWrongPasswordIs401() throws Exception {
		MockHttpSession session = new MockHttpSession();
		MvcResult login = login("wrong-password", session);
		assertThat(login.getResponse().getStatus()).isEqualTo(401);
		assertThat(login.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
		assertThat(login.getResponse().getContentAsString(StandardCharsets.UTF_8))
				.contains("\"error\":\"아이디 또는 비밀번호를 확인해 주세요.\"");

		mvc.perform(get("/api/courses").session(session)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
	}

	@Test
	void loginNeedsCsrf() throws Exception {
		MockHttpSession session = new MockHttpSession();
		mvc.perform(post("/api/auth/login").session(session).contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.content(form(PASSWORD)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").isNotEmpty());
		mvc.perform(get("/api/courses").session(session)).andExpect(status().isUnauthorized());
	}

	@Test
	void apiWithoutLoginIs401WithoutBasicChallenge() throws Exception {
		for (String path : new String[] { "/api/courses", "/api/courses/1/dashboard", "/api/auth/me" }) {
			mvc.perform(get(path)).andExpect(unauthorizedJson());
			// 브라우저 주소창(Accept: text/html)으로 열어도 API는 로그인 화면으로 돌리지 않는다.
			mvc.perform(get(path).accept(MediaType.TEXT_HTML)).andExpect(unauthorizedJson());
		}
	}

	@Test
	void basicAuthNoLongerWorks() throws Exception {
		mvc.perform(get("/api/courses").with(httpBasic(USERNAME, PASSWORD))).andExpect(unauthorizedJson());
		mvc.perform(get("/api/auth/me").with(httpBasic(USERNAME, PASSWORD))).andExpect(unauthorizedJson());
	}

	// 로그인 처리를 Spring Security가 맡더라도 기본 로그인·로그아웃 화면이 따로 생기면 안 된다.
	@Test
	void noDefaultLoginPages() throws Exception {
		mvc.perform(get("/login")).andExpect(status().isNotFound());
		mvc.perform(get("/logout")).andExpect(status().isNotFound());
		mvc.perform(get("/api/auth/login")).andExpect(status().isNotFound());
	}

	@Test
	void instructorPageRedirectsToLogin() throws Exception {
		MvcResult r = mvc.perform(get("/instructor/course?id=1").accept(MediaType.TEXT_HTML))
				.andExpect(status().isFound())
				.andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
				.andExpect(header().string(HttpHeaders.LOCATION, Matchers.startsWith("/instructor/login?next=")))
				.andReturn();
		String next = r.getResponse().getHeader(HttpHeaders.LOCATION).substring("/instructor/login?next=".length());
		assertThat(URLDecoder.decode(next, StandardCharsets.UTF_8)).isEqualTo("/instructor/course?id=1");

		mvc.perform(get("/instructor/login")).andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("login-page")));
	}

	@Test
	void logoutEndsSession() throws Exception {
		MvcResult login = login(PASSWORD, null);
		MockHttpSession session = session(login);
		Cookie csrf = lastCookie(login, CSRF_COOKIE);

		// 다른 사이트가 강사를 몰래 로그아웃시키지 못한다.
		mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isForbidden());
		mvc.perform(get("/api/courses").session(session)).andExpect(status().isOk());

		mvc.perform(post("/api/auth/logout").session(session).cookie(csrf).header(CSRF_HEADER, csrf.getValue()))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/courses").session(session)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
	}

	@Test
	void instructorPostNeedsCsrf() throws Exception {
		MvcResult login = login(PASSWORD, null);
		MockHttpSession session = session(login);
		Cookie csrf = lastCookie(login, CSRF_COOKIE);
		String end = "/api/courses/987654/end";

		mvc.perform(post(end).session(session)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").isNotEmpty());
		// 쿠키만 있고 헤더가 없는 요청이 다른 사이트의 폼 전송이다.
		mvc.perform(post(end).session(session).cookie(csrf)).andExpect(status().isForbidden());
		mvc.perform(post(end).session(session).cookie(csrf).header(CSRF_HEADER, csrf.getValue() + "x"))
				.andExpect(status().isForbidden());

		mvc.perform(post(end).session(session).cookie(csrf).header(CSRF_HEADER, csrf.getValue()))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
	}

	// 학생 화면과 IDE 확장은 쿠키 로그인을 쓰지 않는다(토큰은 경로, 컨테이너 키는 헤더). CSRF 없이 지금처럼 동작한다.
	@Test
	void studentPathsSkipCsrf() throws Exception {
		mvc.perform(post("/api/join").contentType(MediaType.APPLICATION_JSON)
						.content("{\"entryCode\":\"NOPE00\",\"name\":\"철수\",\"pin\":\"1234\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value(Matchers.containsString("수업 코드")));
		mvc.perform(post("/api/s/no-such-token/help").contentType(MediaType.APPLICATION_JSON)
						.content("{\"errorText\":\"x\"}"))
				.andExpect(status().isNotFound());
		mvc.perform(post("/api/ide/help").contentType(MediaType.APPLICATION_JSON).content("{\"errorText\":\"x\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
		mvc.perform(post("/l/no-such-token/install.sh"))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
	}

	@Test
	void sessionIdChangesOnLogin() throws Exception {
		MockHttpSession session = new MockHttpSession();
		String before = session.getId();

		MvcResult login = login(PASSWORD, session);
		assertThat(login.getResponse().getStatus()).isEqualTo(204);
		HttpSession after = login.getRequest().getSession(false);
		assertThat(after).isNotNull();
		assertThat(after.getId()).isNotEqualTo(before);
	}

	private static ResultMatcher unauthorizedJson() {
		return result -> {
			status().isUnauthorized().match(result);
			header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE).match(result);
			jsonPath("$.error").isNotEmpty().match(result);
		};
	}

	// 브라우저처럼 먼저 아무 요청으로 CSRF 쿠키를 받고, 그 값을 헤더로 붙여 폼으로 로그인한다.
	private MvcResult login(String password, MockHttpSession session) throws Exception {
		Cookie csrf = lastCookie(mvc.perform(get("/api/auth/me")).andReturn(), CSRF_COOKIE);
		MockHttpServletRequestBuilder request = post("/api/auth/login")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.content(form(password))
				.cookie(csrf)
				.header(CSRF_HEADER, csrf.getValue());
		if (session != null) {
			// 브라우저가 세션 쿠키로 그 세션 ID를 보낸 것처럼 한다. 요청한 세션 ID가 있어야 로그인 때 ID를 바꾼다.
			String requestedId = session.getId();
			request.session(session).with(r -> {
				r.setRequestedSessionId(requestedId);
				return r;
			});
		}
		return mvc.perform(request).andReturn();
	}

	private static String form(String password) {
		return "username=" + URLEncoder.encode(USERNAME, StandardCharsets.UTF_8)
				+ "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
	}

	private static MockHttpSession session(MvcResult result) {
		HttpSession session = result.getRequest().getSession(false);
		assertThat(session).isInstanceOf(MockHttpSession.class);
		return (MockHttpSession) session;
	}

	// 로그인은 CSRF 값을 새로 만든다(옛 쿠키를 지우는 쿠키 다음에 새 쿠키). 브라우저처럼 마지막 것을 쓴다.
	private static Cookie lastCookie(MvcResult result, String name) {
		Cookie found = null;
		for (Cookie cookie : result.getResponse().getCookies()) {
			if (cookie.getName().equals(name)) {
				found = cookie;
			}
		}
		assertThat(found).as(name + " 쿠키").isNotNull();
		assertThat(found.getValue()).as(name + " 값").isNotEmpty();
		return found;
	}
}
