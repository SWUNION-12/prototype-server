package com.swunion.lab.session;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StudentController {

	public record JoinRequest(String entryCode, String name) {
	}

	private final SessionService service;

	public StudentController(SessionService service) {
		this.service = service;
	}

	@PostMapping("/api/join")
	Map<String, String> join(@RequestBody JoinRequest req) {
		return Map.of("token", service.join(req.entryCode(), req.name()).getToken());
	}

	// containerId, 다른 학생의 토큰, 파일 경로는 싣지 않는다.
	@GetMapping("/api/s/{token}")
	Map<String, Object> me(@PathVariable String token) {
		StudentSession s = service.byToken(token);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", s.getDisplayName());
		m.put("courseName", s.getCourse().getName());
		m.put("ideUrl", service.ideUrl(s));
		m.put("idePassword", s.getIdePassword());
		m.put("envStatus", s.getEnvStatus());
		m.put("issues", service.issues(s));
		m.put("needsHelp", s.isNeedsHelp());
		m.put("latestHelp", null);
		return m;
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
