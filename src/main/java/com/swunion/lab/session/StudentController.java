package com.swunion.lab.session;

import com.swunion.lab.help.CourseEndedException;
import com.swunion.lab.help.HelpBusyException;
import com.swunion.lab.help.HelpRequest;
import com.swunion.lab.help.HelpService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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
	@PostMapping("/api/join")
	ResponseEntity<Map<String, Object>> join(@RequestBody JoinRequest req) {
		JoinResult r = service.join(req.entryCode(), req.name(), req.pin());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("token", r.session().getToken());
		body.put("resumed", r.resumed());
		return ResponseEntity.status(r.resumed() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
	}

	// containerId, 다른 학생의 토큰, 파일 경로, 컨테이너 키는 싣지 않는다.
	@GetMapping("/api/s/{token}")
	Map<String, Object> me(@PathVariable String token) {
		StudentSession s = service.byToken(token);
		Map<String, Object> m = StudentStatusView.of(s, service.issues(s), service.issueTexts(s),
				help.latest(s.getId()));
		// IDE 패널과 달리 학생 화면은 IDE로 가는 주소와 비밀번호를 보여 준다.
		m.put("ideUrl", service.ideUrl(s));
		m.put("idePassword", s.getIdePassword());
		return m;
	}

	@PostMapping("/api/s/{token}/help")
	ResponseEntity<Map<String, Object>> help(@PathVariable String token, @RequestBody(required = false) HelpBody body) {
		HelpRequest h = help.request(token, body == null ? null : body.errorText());
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("helpId", h.getId()));
	}

	@PostMapping("/api/s/{token}/escalate")
	ResponseEntity<Void> escalate(@PathVariable String token) {
		help.escalate(token);
		return ResponseEntity.noContent().build();
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

	@ExceptionHandler(SessionNotFoundException.class)
	ResponseEntity<Void> notFound() {
		return ResponseEntity.notFound().build();
	}
}
