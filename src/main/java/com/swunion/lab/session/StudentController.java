package com.swunion.lab.session;

import com.swunion.lab.help.CourseEndedException;
import com.swunion.lab.help.HelpBusyException;
import com.swunion.lab.help.HelpRequest;
import com.swunion.lab.help.HelpService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 학생 화면 API. 입장하면 세션 토큰을 HttpOnly 쿠키(LAB_STUDENT)로 주고, 그 뒤 학생 API(/api/me/**)는 쿠키로 학생을
 * 알아본다. 토큰은 주소에도 응답 본문에도 싣지 않는다. /api/me/** 의 POST는 CSRF 검사를 받는다(SecurityConfig).
 */
@RestController
public class StudentController {

	public record JoinRequest(String entryCode, String name, String pin) {

		// DEBUG 요청 로그가 본문 객체를 찍어도 PIN은 남지 않게 가린다. 비었는지만 보인다.
		@Override
		public String toString() {
			return "JoinRequest[entryCode=" + entryCode + ", name=" + name + ", pin=" + (pin == null ? null : "****")
					+ "]";
		}
	}

	public record HelpBody(String errorText) {
	}

	private final SessionService service;
	private final HelpService help;

	public StudentController(SessionService service, HelpService help) {
		this.service = service;
		this.help = help;
	}

	// 새 세션은 201, 같은 이름과 PIN으로 돌아오면 200. PIN 틀림 403, 잠김 429, 형식 오류 400은 JoinException이 정한다.
	// 재입장은 토큰을 바꾸므로 같은 학생이 예전에 쓰던 브라우저의 쿠키는 이때부터 404를 받는다.
	@PostMapping("/api/join")
	ResponseEntity<Map<String, Object>> join(@RequestBody JoinRequest req, HttpServletRequest http) {
		JoinResult r = service.join(req.entryCode(), req.name(), req.pin());
		return ResponseEntity.status(r.resumed() ? HttpStatus.OK : HttpStatus.CREATED)
				.header(HttpHeaders.SET_COOKIE, StudentCookie.issue(r.session().getToken(), http.isSecure()).toString())
				.body(Map.of("resumed", r.resumed()));
	}

	// containerId, 토큰, 파일 경로, 컨테이너 키는 싣지 않는다.
	@GetMapping("/api/me")
	Map<String, Object> me(@CookieValue(name = StudentCookie.NAME, required = false) String token) {
		StudentSession s = service.byToken(token);
		Map<String, Object> m = StudentStatusView.of(s, service.issues(s), service.issueTexts(s),
				help.latest(s.getId()));
		// IDE 패널과 달리 학생 화면은 IDE로 가는 주소를 보여 준다. 포트 기록이 없으면(만드는 중, 멈춤) null이다. 비밀번호는 code-server가 비밀번호를 묻는 컨테이너일
		// 때만 싣는다. proxy 방식 컨테이너는 Caddy가 쿠키로 주인을 확인하므로 화면에 비밀번호 칸이 없다.
		m.put("ideUrl", service.ideUrl(s));
		if (IdeAuthMode.of(s) == IdeAuthMode.PASSWORD) {
			m.put("idePassword", s.getIdePassword());
		}
		return m;
	}

	@PostMapping("/api/me/help")
	ResponseEntity<Map<String, Object>> help(@CookieValue(name = StudentCookie.NAME, required = false) String token,
			@RequestBody(required = false) HelpBody body) {
		HelpRequest h = help.request(service.byToken(token), body == null ? null : body.errorText());
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("helpId", h.getId()));
	}

	@PostMapping("/api/me/escalate")
	ResponseEntity<Void> escalate(@CookieValue(name = StudentCookie.NAME, required = false) String token) {
		help.escalate(service.byToken(token));
		return ResponseEntity.noContent().build();
	}

	// 나가기. 유효한 쿠키면 서버 토큰을 바꿔 그 쿠키 값과 나눠 준 다운로드 코드를 끊고, 브라우저 쿠키를 지운다.
	// 세션(컨테이너, 코드)은 그대로라 같은 이름과 PIN으로 다시 들어올 수 있다. 쿠키가 이미 없거나 무효여도 204다.
	@PostMapping("/api/me/logout")
	ResponseEntity<Void> logout(@CookieValue(name = StudentCookie.NAME, required = false) String token,
			HttpServletRequest http) {
		service.signOut(token);
		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, StudentCookie.clear(http.isSecure()).toString())
				.build();
	}

	@ExceptionHandler(HelpBusyException.class)
	ResponseEntity<Map<String, String>> busy(HelpBusyException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
	}

	@ExceptionHandler(CourseEndedException.class)
	ResponseEntity<Map<String, String>> courseEnded(CourseEndedException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
	}

	@ExceptionHandler(JoinException.class)
	ResponseEntity<Map<String, String>> joinFailed(JoinException e) {
		return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
	}

	// 쿠키가 없을 때와 무효일 때(재입장, 학생·수업 삭제)를 가리지 않는다. 화면은 이 응답에서 "다시 입장" 화면을 보인다.
	@ExceptionHandler(SessionNotFoundException.class)
	ResponseEntity<Map<String, String>> notFound() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", StudentCookie.NOT_FOUND_MESSAGE));
	}
}
