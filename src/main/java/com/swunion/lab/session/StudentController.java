package com.swunion.lab.session;

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

	public record JoinRequest(String entryCode, String name) {
	}

	public record HelpBody(String errorText) {
	}

	private final SessionService service;
	private final HelpService help;

	public StudentController(SessionService service, HelpService help) {
		this.service = service;
		this.help = help;
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
		m.put("latestHelp", latestHelp(help.latest(s.getId())));
		return m;
	}

	// errorText는 돌려주지 않는다. 응답을 작게 유지한다.
	private static Map<String, Object> latestHelp(HelpRequest h) {
		if (h == null) {
			return null;
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", h.getId());
		m.put("status", h.getStatus());
		m.put("errorType", h.getErrorType());
		m.put("explanation", h.getExplanation());
		m.put("escalated", h.isEscalated());
		m.put("createdAt", h.getCreatedAt());
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

	@ExceptionHandler(JoinException.class)
	ResponseEntity<Map<String, String>> joinFailed(JoinException e) {
		return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
	}

	@ExceptionHandler(SessionNotFoundException.class)
	ResponseEntity<Void> notFound() {
		return ResponseEntity.notFound().build();
	}
}
