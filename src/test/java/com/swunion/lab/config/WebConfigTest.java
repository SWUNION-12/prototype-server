package com.swunion.lab.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.swunion.lab.session.StudentController;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.resource.ResourceResolverChain;

// 정적 파일은 src/test/resources/static의 고정 파일이다. main에는 정적 파일이 없다(jar를 만들 때만 web/out이 들어온다).
@SpringBootTest(properties = {
		"spring.config.import=optional:file:./build/no-such.env[.properties]",
		"spring.datasource.url=jdbc:h2:mem:webconfig"
})
@AutoConfigureMockMvc
class WebConfigTest {

	@Autowired
	MockMvc mvc;

	@Test
	void extensionlessPathServesHtml() throws Exception {
		mvc.perform(get("/join")).andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
				.andExpect(content().string(containsString("join-page")));
	}

	@Test
	void labWithQueryServesLab() throws Exception {
		mvc.perform(get("/lab?t=abc")).andExpect(status().isOk())
				.andExpect(content().string(containsString("lab-page")));
	}

	// 고정 파일에 index.html이 있어도(실제 정적 빌드도 만든다) Boot의 welcome page 대신 리다이렉트가 이긴다.
	@Test
	void rootRedirectsToJoin() throws Exception {
		mvc.perform(get("/")).andExpect(status().is3xxRedirection())
				.andExpect(header().string(HttpHeaders.LOCATION, endsWith("/join")));
	}

	@Test
	void nextAssetsArePublic() throws Exception {
		mvc.perform(get("/_next/static/app.js")).andExpect(status().isOk())
				.andExpect(content().string(containsString("next-asset")));
	}

	// 해시 파일만 오래 캐시한다. Spring Security가 no-store로 덮어쓰지 않아야 하고, HTML·RSC는 캐시하지 않는다.
	@Test
	void hashedAssetsAreCachedAndPagesAreNot() throws Exception {
		mvc.perform(get("/_next/static/app.js"))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=31536000")))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("immutable")))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, not(containsString("no-store"))));
		mvc.perform(get("/join"))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
		mvc.perform(get("/instructor/login.txt")).andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
	}

	// 옛 주소는 쿼리(학생 토큰, 수업 코드)를 그대로 새 화면으로 넘긴다.
	@Test
	void oldPagesRedirectKeepingQuery() throws Exception {
		mvc.perform(get("/join.html?code=ABC123")).andExpect(status().isFound())
				.andExpect(header().string(HttpHeaders.LOCATION, "/join?code=ABC123"));
		// 옛 학생 주소의 토큰은 새 주소로 옮기지 않는다(학생은 쿠키로 알아본다).
		mvc.perform(get("/student.html?t=tok")).andExpect(status().isFound())
				.andExpect(header().string(HttpHeaders.LOCATION, "/lab"));
		mvc.perform(get("/join.html")).andExpect(status().isFound())
				.andExpect(header().string(HttpHeaders.LOCATION, "/join"));
	}

	// /instructor.html은 보안 규칙에 걸려 로그인 없이는 리다이렉트도 받지 못한다. 로그인한 강사만 /instructor로 간다.
	@Test
	void oldInstructorPageDoesNotBypassLogin() throws Exception {
		mvc.perform(get("/instructor.html")).andExpect(status().is3xxRedirection())
				.andExpect(header().string(HttpHeaders.LOCATION, containsString("/instructor/login")));
		mvc.perform(get("/dashboard.html?course=3").with(user("teacher"))).andExpect(status().isFound())
				.andExpect(header().string(HttpHeaders.LOCATION, "/instructor?course=3"));
		mvc.perform(get("/instructor.html").with(user("teacher"))).andExpect(status().isFound())
				.andExpect(header().string(HttpHeaders.LOCATION, "/instructor"));
		mvc.perform(get("/instructor")).andExpect(status().is3xxRedirection())
				.andExpect(header().string(HttpHeaders.LOCATION, containsString("/instructor/login")));
	}

	@Test
	void unknownPathIs404() throws Exception {
		mvc.perform(get("/nope")).andExpect(status().isNotFound());
		// 컨트롤러가 먼저 받으므로 정적 대체가 API 404를 가로채지 않는다. 404만 봐서는 둘을 구별할 수 없어 처리기 종류를 본다.
		mvc.perform(get("/api/me")).andExpect(status().isNotFound())
				.andExpect(handler().handlerType(StudentController.class));
	}

	@Test
	void trailingSlashIsNotServed() throws Exception {
		mvc.perform(get("/join/")).andExpect(status().isNotFound());
		mvc.perform(get("/join.html/")).andExpect(status().isNotFound());
	}

	@Test
	void htmlFallbackOnlyWhenLastSegmentHasNoDot() {
		List<String> tried = new ArrayList<>();
		ResourceResolverChain chain = new ResourceResolverChain() {
			@Override
			public Resource resolveResource(HttpServletRequest request, String requestPath,
					List<? extends Resource> locations) {
				tried.add(requestPath);
				return null;
			}

			@Override
			public String resolveUrlPath(String resourcePath, List<? extends Resource> locations) {
				return null;
			}
		};
		WebConfig.HtmlFallbackResolver resolver = new WebConfig.HtmlFallbackResolver();

		resolver.resolveResource(null, "instructor/course", List.of(), chain);
		resolver.resolveResource(null, "v1.2/app", List.of(), chain);
		resolver.resolveResource(null, "_next/static/app.js", List.of(), chain);
		resolver.resolveResource(null, "instructor.txt", List.of(), chain);
		resolver.resolveResource(null, "instructor/", List.of(), chain);

		// 점 없는 경로 자체(디렉터리일 수 있다)와 끝 슬래시 경로는 찾지 않는다.
		assertThat(tried).containsExactly("instructor/course.html", "v1.2/app.html", "_next/static/app.js",
				"instructor.txt");
	}
}
