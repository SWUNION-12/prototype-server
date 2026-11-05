package com.swunion.lab.session;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** 학생 쿠키(LAB_STUDENT)를 싣고 읽는 테스트 도우미. 여러 패키지의 MockMvc 테스트가 함께 쓴다. */
public final class StudentRequests {

	static final String XSRF = "test-xsrf-value";

	private StudentRequests() {
	}

	public static Cookie cookie(String token) {
		return new Cookie(StudentCookie.NAME, token);
	}

	/**
	 * 화면이 하는 그대로 XSRF-TOKEN 쿠키와 같은 값의 X-XSRF-TOKEN 헤더를 싣는다. spring-security-test의 csrf()는 컨텍스트의
	 * CSRF 저장소를 바꿔 끼워 같은 컨텍스트의 실제 쿠키 흐름 테스트를 깨뜨리므로 쓰지 않는다.
	 */
	public static RequestPostProcessor xsrf() {
		return request -> {
			List<Cookie> cookies = new ArrayList<>(
					request.getCookies() == null ? List.of() : Arrays.asList(request.getCookies()));
			cookies.add(new Cookie("XSRF-TOKEN", XSRF));
			request.setCookies(cookies.toArray(Cookie[]::new));
			request.addHeader("X-XSRF-TOKEN", XSRF);
			return request;
		};
	}

	/** 응답의 LAB_STUDENT Set-Cookie 헤더 원문. 없으면 null. */
	public static String setCookie(MvcResult result) {
		List<String> found = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
				.filter(h -> h.startsWith(StudentCookie.NAME + "="))
				.toList();
		assertThat(found.size()).as("LAB_STUDENT Set-Cookie 수").isLessThanOrEqualTo(1);
		return found.isEmpty() ? null : found.get(0);
	}

	/** 입장 응답이 준 쿠키 값(세션 토큰). */
	public static String token(MvcResult result) {
		String header = setCookie(result);
		assertThat(header).as("LAB_STUDENT 쿠키").isNotNull();
		String value = header.substring(StudentCookie.NAME.length() + 1, header.indexOf(';'));
		assertThat(value).matches("[0-9a-f]{64}");
		return value;
	}
}
