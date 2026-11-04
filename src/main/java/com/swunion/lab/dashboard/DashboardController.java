package com.swunion.lab.dashboard;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /api/courses/**는 SecurityConfig가 강사 인증을 요구한다. */
@RestController
@RequestMapping("/api/courses/{courseId}")
public class DashboardController {

	private final DashboardService service;

	public DashboardController(DashboardService service) {
		this.service = service;
	}

	@GetMapping("/dashboard")
	ResponseEntity<DashboardView> dashboard(@PathVariable long courseId) {
		return service.dashboard(courseId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
	}

	@PostMapping("/sessions/{sessionId}/resolve")
	ResponseEntity<Void> resolve(@PathVariable long courseId, @PathVariable long sessionId) {
		return service.resolve(courseId, sessionId) ? ResponseEntity.noContent().build()
				: ResponseEntity.notFound().build();
	}
}
