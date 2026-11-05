package com.swunion.lab.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@ExtendWith(OutputCaptureExtension.class)
class SecurityConfigTest {

	static final String WARNING = "학생에게 IDE를 주기 전에 INSTRUCTOR_PASSWORD를 바꾸세요";

	private static LabProperties props(String password) {
		return new LabProperties("instructor", password, "m", 15, "http://localhost:8080", "127.0.0.1", Path.of("."),
				"b:1", "t/course-", "swunion-s", "http://host.docker.internal:8080");
	}

	@Test
	void defaultPasswordLogsWarning(CapturedOutput out) {
		new SecurityConfig().userDetailsService(props("instructor"),
				PasswordEncoderFactories.createDelegatingPasswordEncoder());
		assertThat(out.getAll()).contains("WARN").contains(WARNING);
	}

	@Test
	void changedPasswordLogsNothing(CapturedOutput out) {
		new SecurityConfig().userDetailsService(props("Changed-Pw-42"),
				PasswordEncoderFactories.createDelegatingPasswordEncoder());
		assertThat(out.getAll()).doesNotContain(WARNING).doesNotContain("Changed-Pw-42");
	}

	// 출력 캡처는 클래스 전체 출력을 모으므로, 이 컨텍스트가 기본 비밀번호 경고를 찍으면 위 테스트가 실행 순서에 따라 깨진다.
	// 그래서 기본값이 아닌 비밀번호로 띄운다. 정적 파일은 src/test/resources/static의 고정 파일이다.
	// 강사 파일의 구분 글자는 모두 "instructor-"로 시작하고, 공개인 로그인 화면 파일은 "login-"으로 시작한다.
	@Nested
	@SpringBootTest(properties = {
			"spring.config.import=optional:file:./build/no-such.env[.properties]",
			"spring.datasource.url=jdbc:h2:mem:securitypaths",
			"lab.instructor-password=" + Paths.PASSWORD
	})
	@AutoConfigureMockMvc
	class Paths {

		static final String PASSWORD = "Paths-Test-Pw-7";
		static final String LOGIN = "/instructor/login";

		// 화면 주소. 로그인 뒤 돌아올 수 있게 원래 경로와 쿼리를 next로 싣는다.
		static final Map<String, String> PAGES = Map.of(
				"/instructor", "instructor-page",
				"/instructor/new", "instructor-new-page",
				"/instructor/course?id=1", "instructor-course-page");

		// 정적 빌드의 파일 주소(.html 직접 주소, RSC 조각). 돌아갈 화면이 아니므로 next 없이 로그인 화면으로 보낸다.
		static final Map<String, String> FILES = Map.of(
				"/instructor.html", "instructor-page",
				"/instructor.txt", "instructor-rsc",
				"/instructor/course.txt", "instructor-course-rsc",
				"/instructor/course/__next.instructor.course.__PAGE__.txt", "instructor-course-segment");

		static final Map<String, String> LOGIN_FILES = Map.of(
				"/instructor/login", "login-page",
				"/instructor/login.html", "login-page",
				"/instructor/login.txt", "login-rsc",
				"/instructor/login/__next.instructor.login.__PAGE__.txt", "login-segment");

		@Autowired
		MockMvc mvc;

		@Test
		void instructorPagesRedirectToLoginWithNext() throws Exception {
			for (Map.Entry<String, String> page : PAGES.entrySet()) {
				String next = URLEncoder.encode(page.getKey(), StandardCharsets.UTF_8);
				expectLogin(mvc.perform(get(page.getKey()).accept(MediaType.TEXT_HTML)), LOGIN + "?next=" + next);
				mvc.perform(get(page.getKey()).with(user("instructor")))
						.andExpect(status().isOk())
						.andExpect(content().string(containsString(page.getValue())));
			}
		}

		@Test
		void instructorFilesRedirectToLoginWithoutNext() throws Exception {
			for (Map.Entry<String, String> file : FILES.entrySet()) {
				expectLogin(mvc.perform(get(file.getKey())), LOGIN);
				mvc.perform(get(file.getKey()).with(user("instructor")))
						.andExpect(status().isOk())
						.andExpect(content().string(containsString(file.getValue())));
			}
		}

		// Next 화면 이동이 받아 오는 RSC 요청(RSC: 1)은 화면 주소로 와도 next로 싣지 않는다.
		@Test
		void rscRequestDoesNotBecomeNext() throws Exception {
			expectLogin(mvc.perform(get("/instructor/course?id=1").header("RSC", "1")), LOGIN);
		}

		@Test
		void headRequestNeedsLogin() throws Exception {
			expectLogin(mvc.perform(head("/instructor/course?id=1")),
					LOGIN + "?next=" + URLEncoder.encode("/instructor/course?id=1", StandardCharsets.UTF_8));
		}

		@Test
		void loginPageIsPublic() throws Exception {
			for (Map.Entry<String, String> file : LOGIN_FILES.entrySet()) {
				mvc.perform(get(file.getKey()).accept(MediaType.TEXT_HTML))
						.andExpect(status().isOk())
						.andExpect(content().string(containsString(file.getValue())));
			}
		}

		// 로그인 화면 제외 규칙이 다른 강사 주소를 열지 않는다.
		@Test
		void loginExemptionOpensNothingElse() throws Exception {
			for (String path : List.of("/instructor/loginx", "/instructor/logi", "/instructor/login.html/",
					"/instructor/login.txt/", "/instructor/course/login", "/Instructor/LoginX")) {
				expectLogin(mvc.perform(get(URI.create("http://localhost" + path))), null);
			}
			// 대소문자만 다른 로그인 주소는 로그인 화면 파일만 가리킨다(압축 풀린 classpath는 macOS에서 대소문자를 가리지 않는다).
			for (String path : List.of("/INSTRUCTOR/LOGIN", "/Instructor/Login.TXT")) {
				mvc.perform(get(path)).andExpect(
						r -> assertThat(r.getResponse().getContentAsString()).as(path).doesNotContain("instructor-"));
			}
			assertRejected(List.of("/instructor/login/", "/instructor/login/../course",
					"/instructor/login/%2e%2e/course", "/instructor/login//course", "/instructor/login;x=1",
					"/instructor/login/%2fcourse"));
		}

		@Test
		void traversalDoesNotBypassAuth() throws Exception {
			assertRejected(List.of("/join/../instructor", "/%2e%2e/instructor"));
		}

		// 대소문자, 끝 슬래시, 인코딩한 글자만 다른 주소도 같은 규칙에 걸려 로그인 화면으로 간다. 대소문자를 가리는 매처로
		// 바꾸면 이 테스트가 깨진다(파일 시스템이 대소문자를 가리든 안 가리든).
		@Test
		void instructorLookalikesNeedLogin() throws Exception {
			for (String path : List.of("/INSTRUCTOR", "/Instructor/New", "/instructor.HTML", "/INSTRUCTOR.txt",
					"/Instructor/Course.TXT", "/instructor/", "/instructor.html/", "/instructor.txt/",
					"/instructor/new/", "/%69nstructor", "/%49NSTRUCTOR.html")) {
				expectLogin(mvc.perform(get(URI.create("http://localhost" + path)).accept(MediaType.TEXT_HTML)), null);
			}
		}

		// //, ;, %2e, %2F, ., .. 같은 경로 조작은 방화벽(StrictHttpFirewall)이 400으로 막는다.
		@Test
		void pathTricksDoNotBypassAuth() throws Exception {
			assertRejected(List.of("//instructor", "/instructor;x=1", "/instructor%2ehtml",
					"/instructor%2Fnew", "/join/%2e%2e/instructor", "/./instructor", "/instructor/./new"));
		}

		// location이 null이면 로그인 화면으로 가는지만 본다. 브라우저 로그인 창을 띄우는 WWW-Authenticate는 어디에도 없다.
		private static void expectLogin(ResultActions result, String location) throws Exception {
			result.andExpect(status().isFound())
					.andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
					.andExpect(r -> {
						String actual = r.getResponse().getHeader(HttpHeaders.LOCATION);
						if (location == null) {
							assertThat(actual).as(r.getRequest().getRequestURI())
									.matches("/instructor/login(\\?next=.*)?");
						}
						else {
							assertThat(actual).as(r.getRequest().getRequestURI()).isEqualTo(location);
						}
					})
					.andExpect(r -> assertThat(r.getResponse().getContentAsString()).doesNotContain("instructor-"));
		}

		// 문자열 템플릿으로 넘기면 MockMvc가 %를 다시 인코딩하고 //를 호스트로 읽으므로 완성된 URI로 보낸다.
		private void assertRejected(List<String> paths) throws Exception {
			for (String path : paths) {
				mvc.perform(get(URI.create("http://localhost" + path)).accept(MediaType.TEXT_HTML))
						.andExpect(result -> {
							assertThat(result.getResponse().getStatus()).as(path).isNotEqualTo(200);
							assertThat(result.getResponse().getContentAsString()).as(path).doesNotContain("instructor-");
						});
			}
		}
	}
}
