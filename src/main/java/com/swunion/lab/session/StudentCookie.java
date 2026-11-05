package com.swunion.lab.session;

import java.time.Duration;
import org.springframework.http.ResponseCookie;

/**
 * 학생 브라우저가 자기 세션을 알리는 쿠키. 값은 세션 토큰이다. 주소에 토큰을 싣지 않으려고 쓴다(주소는 메신저 공유,
 * 화면 공유, 브라우저 기록으로 샌다). 화면 스크립트가 읽을 일이 없으므로 HttpOnly이고, 다른 사이트에서 시작한 요청에는
 * 실리지 않도록 SameSite=Strict다. IDE 확인(/ide/<포트>/)도 이 쿠키로 하므로 Path는 /다.
 */
public final class StudentCookie {

	public static final String NAME = "LAB_STUDENT";
	/** 하루 수업을 덮는 길이. 다음 날 브라우저를 다시 열면 PIN으로 다시 입장한다. */
	static final Duration MAX_AGE = Duration.ofHours(12);
	/** 쿠키가 없거나, 재입장으로 토큰이 바뀌었거나, 강사가 학생·수업을 지웠을 때 학생 API가 주는 404 본문. */
	public static final String NOT_FOUND_MESSAGE = "입장 정보를 찾을 수 없습니다. 다시 입장해 주세요.";

	private StudentCookie() {
	}

	/** @param secure HTTPS 요청이면 true. Caddy 뒤에서는 RemoteIpValve가 X-Forwarded-Proto로 정한다. */
	public static ResponseCookie issue(String token, boolean secure) {
		return base(token, secure).maxAge(MAX_AGE).build();
	}

	/** 같은 이름·경로·속성에 Max-Age=0을 주어 브라우저가 지우게 한다. */
	public static ResponseCookie clear(boolean secure) {
		return base("", secure).maxAge(Duration.ZERO).build();
	}

	private static ResponseCookie.ResponseCookieBuilder base(String value, boolean secure) {
		return ResponseCookie.from(NAME, value).httpOnly(true).secure(secure).sameSite("Strict").path("/");
	}
}
