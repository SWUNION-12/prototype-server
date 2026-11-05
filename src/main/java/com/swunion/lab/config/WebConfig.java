package com.swunion.lab.config;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.ResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolverChain;

// 화면은 Next.js 정적 빌드(static/)다. /join, /instructor/course처럼 확장자 없는 주소를 join.html, instructor/course.html로 내보낸다.
// 컨트롤러(/api/**, /l/**)가 리소스 핸들러보다 먼저 매핑되므로 여기까지 오는 요청은 컨트롤러가 받지 않은 주소뿐이다.
// Boot 자동 설정보다 먼저 /**를 등록해야 Boot가 같은 패턴의 기본 핸들러를 따로 만들지 않고 물러난다.
@Configuration(proxyBeanMethods = false)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WebConfig implements WebMvcConfigurer {

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		// 파일 이름에 해시가 붙으므로 바뀌면 주소도 바뀐다. 오래 캐시해도 되고, 학생이 한꺼번에 입장할 때 같은 파일을 다시 받지 않는다.
		// 캐시 헤더를 직접 정해 두면 Spring Security가 no-store를 덧붙이지 않는다. HTML·RSC(.txt)는 아래 핸들러에서 캐시하지 않는다.
		registry.addResourceHandler("/_next/static/**")
				.addResourceLocations("classpath:/static/_next/static/")
				.setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
				.resourceChain(false);
		registry.addResourceHandler("/**")
				.addResourceLocations("classpath:/static/")
				.resourceChain(false)
				.addResolver(new HtmlFallbackResolver());
	}

	// 상태 코드를 정해 두면 Location을 상대 주소 그대로 보낸다. 정하지 않으면 Tomcat이 http://호스트/join으로 바꾸는데,
	// Caddy 뒤(https)에서는 http로 한 번 더 돌아가게 된다.
	@Override
	public void addViewControllers(ViewControllerRegistry registry) {
		registry.addRedirectViewController("/", "/join").setStatusCode(HttpStatus.FOUND);
		// 이미 나눠 준 링크와 열려 있던 탭을 위한 옛 주소. 학생 토큰(?t=)이 쿼리에 있어 쿼리를 그대로 넘긴다.
		// /instructor.html은 /instructor로 보낼 뿐이라 거기서 로그인을 요구받는다(보안 규칙을 비켜 가지 않는다).
		redirectKeepingQuery(registry, "/join.html", "/join");
		redirectKeepingQuery(registry, "/student.html", "/lab");
		redirectKeepingQuery(registry, "/instructor.html", "/instructor");
		redirectKeepingQuery(registry, "/dashboard.html", "/instructor");
	}

	private static void redirectKeepingQuery(ViewControllerRegistry registry, String from, String to) {
		registry.addRedirectViewController(from, to).setStatusCode(HttpStatus.FOUND).setKeepQueryParams(true);
	}

	// 정적 빌드에는 확장자 없는 파일이 없고 같은 이름의 디렉터리(instructor/ 옆 instructor.html)만 있다. 그래서 마지막 조각에 점이 없으면
	// 그 경로 자체는 찾지 않고 .html만 찾는다. 압축 풀린 classpath(로컬 실행, 테스트)에서는 디렉터리가 목록으로 읽히기 때문이다.
	static final class HtmlFallbackResolver implements ResourceResolver {

		@Override
		public Resource resolveResource(HttpServletRequest request, String requestPath,
				List<? extends Resource> locations, ResourceResolverChain chain) {
			// 경로 매핑이 끝 슬래시를 떼고 넘기므로 원래 주소를 본다. 받아 주면 /instructor.html/ 이 instructor.html로 열려
			// 정확한 경로로 건 보안 규칙을 비켜 간다. 정적 빌드에는 디렉터리 주소가 없다.
			if (requestPath.isEmpty() || requestPath.endsWith("/")
					|| (request != null && request.getRequestURI().endsWith("/"))) {
				return null;
			}
			String lastSegment = requestPath.substring(requestPath.lastIndexOf('/') + 1);
			String path = lastSegment.contains(".") ? requestPath : requestPath + ".html";
			Resource resource = chain.resolveResource(request, path, locations);
			return resource == null || isDirectory(resource) ? null : resource;
		}

		@Override
		public String resolveUrlPath(String resourcePath, List<? extends Resource> locations,
				ResourceResolverChain chain) {
			return chain.resolveUrlPath(resourcePath, locations);
		}

		private static boolean isDirectory(Resource resource) {
			try {
				return resource.isFile() && resource.getFile().isDirectory();
			}
			catch (IOException e) {
				return false;
			}
		}
	}
}
