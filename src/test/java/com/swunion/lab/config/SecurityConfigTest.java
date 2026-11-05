package com.swunion.lab.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
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
	@Nested
	@SpringBootTest(properties = {
			"spring.config.import=optional:file:./build/no-such.env[.properties]",
			"spring.datasource.url=jdbc:h2:mem:securitypaths",
			"lab.instructor-password=" + Paths.PASSWORD
	})
	@AutoConfigureMockMvc
	class Paths {

		static final String PASSWORD = "Paths-Test-Pw-7";

		// 브라우저가 처음 강사 화면을 열 때 받는 401 + Basic 요청이 로그인 창을 띄운다.
		static final Map<String, String> ADMIN_PAGES = Map.of(
				"/admin", "admin-page",
				"/admin/new", "admin-new-page",
				"/admin/course?id=1", "admin-course-page",
				"/admin.html", "admin-page",
				"/admin.txt", "admin-rsc");

		@Autowired
		MockMvc mvc;

		@Test
		void adminPagesNeedInstructor() throws Exception {
			for (Map.Entry<String, String> page : ADMIN_PAGES.entrySet()) {
				mvc.perform(get(page.getKey()).accept(MediaType.TEXT_HTML))
						.andExpect(status().isUnauthorized())
						.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Basic")));
				mvc.perform(get(page.getKey()).with(httpBasic("instructor", PASSWORD)))
						.andExpect(status().isOk())
						.andExpect(content().string(containsString(page.getValue())));
			}
		}

		// 옛 강사 HTML을 지울 때 이 테스트도 함께 지운다.
		@Test
		void oldInstructorPagesStillNeedAuth() throws Exception {
			mvc.perform(get("/instructor.html")).andExpect(status().isUnauthorized());
			mvc.perform(get("/dashboard.html")).andExpect(status().isUnauthorized());
		}

		@Test
		void traversalDoesNotBypassAuth() throws Exception {
			assertNoAdminContent(List.of("/join/../admin", "/%2e%2e/admin"));
		}

		// 압축 풀린 classpath(로컬 실행, 이 테스트)는 macOS에서 대소문자를 가리지 않고, 리소스 핸들러는 끝 슬래시를 떼고 찾는다.
		// //, ;, %2e, %2F, ., .. 같은 경로 조작은 방화벽(StrictHttpFirewall)이 400으로 막는다.
		@Test
		void adminLookalikesDoNotBypassAuth() throws Exception {
			assertNoAdminContent(List.of("/ADMIN", "/Admin/New", "/admin.HTML", "/ADMIN.txt", "/admin/", "/admin.html/",
					"/admin.txt/", "/admin/new/", "/%61dmin", "/%41DMIN.html", "//admin", "/admin;x=1",
					"/admin%2ehtml", "/admin%2Fnew", "/join/%2e%2e/admin", "/./admin", "/admin/./new"));
		}

		// 문자열 템플릿으로 넘기면 MockMvc가 %를 다시 인코딩하고 //를 호스트로 읽으므로 완성된 URI로 보낸다.
		private void assertNoAdminContent(List<String> paths) throws Exception {
			for (String path : paths) {
				mvc.perform(get(URI.create("http://localhost" + path)).accept(MediaType.TEXT_HTML)).andExpect(result -> {
					assertThat(result.getResponse().getStatus()).as(path).isNotEqualTo(200);
					assertThat(result.getResponse().getContentAsString()).as(path).doesNotContain("admin-");
				});
			}
		}
	}
}
