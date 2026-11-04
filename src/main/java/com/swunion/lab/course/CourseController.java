package com.swunion.lab.course;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/courses")
public class CourseController {

	public record CreateRequest(String name, String pythonVersion, String packages) {
	}

	private final CourseService service;

	public CourseController(CourseService service) {
		this.service = service;
	}

	@PostMapping
	ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest req) {
		Course c = service.create(req.name(), req.pythonVersion(), req.packages());
		return ResponseEntity.status(HttpStatus.CREATED).body(view(c));
	}

	@GetMapping
	List<Map<String, Object>> list() {
		return service.list().stream().map(CourseController::view).toList();
	}

	@GetMapping("/{id}")
	ResponseEntity<Map<String, Object>> get(@PathVariable long id) {
		Course c = service.get(id);
		return c == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(view(c));
	}

	@ExceptionHandler(InvalidSpecException.class)
	ResponseEntity<Map<String, String>> invalid(InvalidSpecException e) {
		return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
	}

	// 파일 경로(작업 폴더, 이미지 이름 등)는 응답에 싣지 않는다.
	static Map<String, Object> view(Course c) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", c.getId());
		m.put("name", c.getName());
		m.put("pythonVersion", c.getPythonVersion());
		m.put("packages", Arrays.asList(c.getPackagesText().split("\n")));
		m.put("status", c.getStatus());
		m.put("errorSummary", c.getErrorSummary());
		m.put("entryCode", c.getEntryCode());
		m.put("createdAt", c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
		return m;
	}
}
