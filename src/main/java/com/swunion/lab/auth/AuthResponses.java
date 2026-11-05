package com.swunion.lab.auth;

import com.swunion.lab.config.SecurityConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

// 강사 로그인 흐름의 응답. 화면 코드가 fetch로 부르므로 API에는 상태 코드와 {error} JSON만 돌려준다.
// WWW-Authenticate는 어디에서도 보내지 않는다. 보내면 브라우저가 자체 로그인 창을 띄운다.
@Component
public class AuthResponses implements AuthenticationEntryPoint, AuthenticationSuccessHandler,
		AuthenticationFailureHandler, AccessDeniedHandler {

	public static final String LOGIN_PAGE = "/instructor/login";
	static final String BAD_CREDENTIALS = "아이디 또는 비밀번호를 확인해 주세요.";
	static final String LOGIN_REQUIRED = "강사 로그인이 필요합니다.";
	static final String CSRF_REJECTED = "보안 확인 값이 맞지 않습니다. 페이지를 새로고침한 뒤 다시 시도해 주세요.";

	private static final RequestMatcher API = SecurityConfig.paths().matcher("/api/**");

	private final JsonMapper json;

	public AuthResponses(JsonMapper json) {
		this.json = json;
	}

	// 로그인 전 요청. API는 401 JSON, 강사 화면 주소는 로그인 화면으로 보낸다.
	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
			throws IOException {
		if (API.matches(request)) {
			writeError(response, HttpStatus.UNAUTHORIZED, LOGIN_REQUIRED);
			return;
		}
		// Location을 상대 주소로 두려고 sendRedirect를 쓰지 않는다. Tomcat이 http://호스트/...로 바꾼다.
		response.setStatus(HttpStatus.FOUND.value());
		response.setHeader(HttpHeaders.LOCATION, loginLocation(request));
	}

	// 돌아갈 곳(next)은 화면 주소일 때만 싣는다. 정적 빌드의 파일(.html 직접 주소, .txt RSC 조각)이나 Next 화면 이동이
	// 받아 오는 RSC 요청(헤더 RSC: 1)을 next로 실으면 로그인 뒤 화면 대신 그 파일 본문이 열린다.
	static String loginLocation(HttpServletRequest request) {
		String uri = request.getRequestURI();
		String lastSegment = uri.substring(uri.lastIndexOf('/') + 1);
		if (request.getHeader("RSC") != null || lastSegment.contains(".")) {
			return LOGIN_PAGE;
		}
		String query = request.getQueryString();
		String target = query == null ? uri : uri + "?" + query;
		return LOGIN_PAGE + "?next=" + URLEncoder.encode(target, StandardCharsets.UTF_8);
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) {
		response.setStatus(HttpStatus.NO_CONTENT.value());
	}

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException e) throws IOException {
		writeError(response, HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS);
	}

	// 인가 규칙은 로그인 여부만 보므로 여기 오는 것은 CSRF 값이 없거나 틀린 요청이다.
	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
			throws IOException {
		writeError(response, HttpStatus.FORBIDDEN, CSRF_REJECTED);
	}

	private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getOutputStream().write(json.writeValueAsBytes(Map.of("error", message)));
	}
}
