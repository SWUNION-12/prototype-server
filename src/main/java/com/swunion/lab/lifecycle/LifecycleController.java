package com.swunion.lab.lifecycle;

import com.swunion.lab.course.CourseStateException;
import com.swunion.lab.lifecycle.LifecycleService.ConfirmNameMismatchException;
import com.swunion.lab.lifecycle.LifecycleService.DeleteResult;
import com.swunion.lab.lifecycle.LifecycleService.NotFoundException;
import com.swunion.lab.lifecycle.LifecycleService.ResumeResult;
import com.swunion.lab.session.SessionNotFoundException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 수업·학생 수명 관리. /api/courses/**는 SecurityConfig가 강사 인증을 요구한다. 오류는 모두 {error}. */
@RestController
@RequestMapping("/api/courses/{courseId}")
public class LifecycleController {

	public record DeleteCourseRequest(String confirmName) {
	}

	private final LifecycleService service;

	public LifecycleController(LifecycleService service) {
		this.service = service;
	}

	@PostMapping("/resume")
	ResumeResult resume(@PathVariable long courseId) {
		return service.resume(courseId);
	}

	/** 본문이 없으면 확인 이름이 없는 것으로 보고 400을 준다. */
	@DeleteMapping
	DeleteResult deleteCourse(@PathVariable long courseId,
			@RequestBody(required = false) DeleteCourseRequest req) {
		return service.deleteCourse(courseId, req == null ? null : req.confirmName());
	}

	@PostMapping("/sessions/{sessionId}/restart")
	Map<String, Boolean> restart(@PathVariable long courseId, @PathVariable long sessionId) {
		return Map.of("ok", service.restartStudent(courseId, sessionId));
	}

	@DeleteMapping("/sessions/{sessionId}")
	DeleteResult deleteStudent(@PathVariable long courseId, @PathVariable long sessionId) {
		return service.deleteStudent(courseId, sessionId);
	}

	@PostMapping("/sessions/{sessionId}/reset-pin")
	ResponseEntity<Void> resetPin(@PathVariable long courseId, @PathVariable long sessionId) {
		service.resetPin(courseId, sessionId);
		return ResponseEntity.noContent().build();
	}

	@ExceptionHandler(NotFoundException.class)
	ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
		return error(HttpStatus.NOT_FOUND, e.getMessage());
	}

	// 소속을 확인한 뒤 세션이 지워진 경우(동시에 누른 학생 삭제 등). 예외 문구가 영어라 직접 쓴다.
	@ExceptionHandler(SessionNotFoundException.class)
	ResponseEntity<Map<String, String>> sessionGone(SessionNotFoundException e) {
		return error(HttpStatus.NOT_FOUND, "이 수업의 학생을 찾을 수 없습니다.");
	}

	@ExceptionHandler(ConfirmNameMismatchException.class)
	ResponseEntity<Map<String, String>> nameMismatch(ConfirmNameMismatchException e) {
		return error(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
		return error(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다.");
	}

	@ExceptionHandler(CourseStateException.class)
	ResponseEntity<Map<String, String>> conflict(CourseStateException e) {
		return error(HttpStatus.CONFLICT, e.getMessage());
	}

	private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
		return ResponseEntity.status(status).body(Map.of("error", message));
	}
}
