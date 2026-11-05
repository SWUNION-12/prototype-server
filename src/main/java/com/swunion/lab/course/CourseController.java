package com.swunion.lab.course;

import com.swunion.lab.session.SessionRepository;
import java.util.Arrays;
import java.util.HashMap;
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
	private final SessionRepository sessions;

	public CourseController(CourseService service, SessionRepository sessions) {
		this.service = service;
		this.sessions = sessions;
	}

	@PostMapping
	ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest req) {
		Course c = service.create(req.name(), req.pythonVersion(), req.packages());
		return ResponseEntity.status(HttpStatus.CREATED).body(view(c, 0));
	}

	@GetMapping
	List<Map<String, Object>> list() {
		// 수업마다 세지 않고 한 번의 group by로 센다. 학생이 없는 수업은 맵에 없으므로 0이다.
		Map<Long, Long> counts = new HashMap<>();
		sessions.countGroupedByCourse().forEach(r -> counts.put(r.getCourseId(), r.getTotal()));
		return service.list().stream().map(c -> view(c, counts.getOrDefault(c.getId(), 0L))).toList();
	}

	@GetMapping("/{id}")
	ResponseEntity<Map<String, Object>> get(@PathVariable long id) {
		Course c = service.get(id);
		return c == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(view(c, sessions.countByCourse_Id(c.getId())));
	}

	/** 컨테이너만 멈춘다. 볼륨은 남는다. */
	@PostMapping("/{id}/end")
	ResponseEntity<Map<String, Object>> end(@PathVariable long id) {
		CourseService.EndResult r = service.end(id);
		if (r == null) {
			return ResponseEntity.notFound().build();
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("status", CourseStatus.ENDED);
		m.put("stopped", r.stopped());
		m.put("failed", r.failed());
		return ResponseEntity.ok(m);
	}

	@ExceptionHandler(InvalidSpecException.class)
	ResponseEntity<Map<String, String>> invalid(InvalidSpecException e) {
		return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
	}

	@ExceptionHandler(CourseStateException.class)
	ResponseEntity<Map<String, String>> conflict(CourseStateException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
	}

	// 파일 경로(작업 폴더, 이미지 이름 등)는 응답에 싣지 않는다.
	static Map<String, Object> view(Course c, long studentCount) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", c.getId());
		m.put("name", c.getName());
		m.put("pythonVersion", c.getPythonVersion());
		m.put("packages", Arrays.asList(c.getPackagesText().split("\n")));
		m.put("status", c.getStatus());
		m.put("studentCount", studentCount);
		m.put("errorSummary", c.getErrorSummary());
		m.put("entryCode", c.getEntryCode());
		m.put("createdAt", c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
		return m;
	}
}
