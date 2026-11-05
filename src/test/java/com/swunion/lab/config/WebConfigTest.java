package com.swunion.lab.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

// 정적 파일은 src/test/resources/static의 고정 파일이다. 테스트 classpath가 main보다 앞이라 main의 옛 join.html을 가린다.
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
		mvc.perform(get("/join.html")).andExpect(status().isOk())
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

	@Test
	void unknownPathIs404() throws Exception {
		mvc.perform(get("/nope")).andExpect(status().isNotFound());
		// 컨트롤러가 먼저 받으므로 정적 대체가 API 404를 가로채지 않는다.
		mvc.perform(get("/api/s/없는토큰")).andExpect(status().isNotFound());
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

		resolver.resolveResource(null, "admin/course", List.of(), chain);
		resolver.resolveResource(null, "v1.2/app", List.of(), chain);
		resolver.resolveResource(null, "_next/static/app.js", List.of(), chain);
		resolver.resolveResource(null, "admin.txt", List.of(), chain);
		resolver.resolveResource(null, "admin/", List.of(), chain);

		// 점 없는 경로 자체(디렉터리일 수 있다)와 끝 슬래시 경로는 찾지 않는다.
		assertThat(tried).containsExactly("admin/course.html", "v1.2/app.html", "_next/static/app.js", "admin.txt");
	}
}
