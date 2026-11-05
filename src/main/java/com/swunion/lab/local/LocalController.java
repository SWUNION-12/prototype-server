package com.swunion.lab.local;

import com.swunion.lab.session.SessionNotFoundException;
import com.swunion.lab.session.SessionService;
import com.swunion.lab.session.StudentCookie;
import com.swunion.lab.session.StudentSession;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 PC로 이어가기. 학생 화면은 쿠키(LAB_STUDENT)로 /api/me/** 를 부르고, 터미널은 쿠키가 없으므로 화면이 받아 준
 * 10분짜리 다운로드 코드로 /l/{code}/** 를 부른다. 어느 주소에도 세션 토큰은 들어가지 않는다.
 */
@RestController
public class LocalController {

	public record DiagnosticsBody(String os, String text) {
	}

	public record VerifyBody(String text) {
	}

	private static final MediaType TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);

	private final LocalService service;
	private final SessionService sessions;

	public LocalController(LocalService service, SessionService sessions) {
		this.service = service;
		this.sessions = sessions;
	}

	// 코드를 만드는 요청이라 GET이 아니라 POST다(CSRF 검사를 받는다).
	@PostMapping("/api/me/local/link")
	Map<String, String> link(@CookieValue(name = StudentCookie.NAME, required = false) String token,
			@RequestParam String os) {
		return service.link(sessions.byToken(token), os);
	}

	@PostMapping("/api/me/local/diagnostics")
	ResponseEntity<Void> diagnostics(@CookieValue(name = StudentCookie.NAME, required = false) String token,
			@RequestBody DiagnosticsBody body) {
		service.saveDiagnostics(sessions.byToken(token), body.os(), body.text());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/api/me/local/verify")
	Map<String, Object> verify(@CookieValue(name = StudentCookie.NAME, required = false) String token,
			@RequestBody VerifyBody body) {
		return service.verify(sessions.byToken(token), body.text());
	}

	// 화면의 코드 zip 버튼(수업 종료 화면 포함). 브라우저가 쿠키를 싣고 받는다.
	@GetMapping("/api/me/code.zip")
	ResponseEntity<byte[]> myCodeZip(@CookieValue(name = StudentCookie.NAME, required = false) String token) {
		return zip(sessions.byToken(token));
	}

	@GetMapping("/l/{code}/install.sh")
	ResponseEntity<String> installSh(@PathVariable String code) {
		return text(service.installSh(service.byCode(code), code));
	}

	@GetMapping("/l/{code}/install.ps1")
	ResponseEntity<String> installPs1(@PathVariable String code) {
		return text(service.installPs1(service.byCode(code), code));
	}

	@GetMapping("/l/{code}/files/{name}")
	ResponseEntity<String> file(@PathVariable String code, @PathVariable String name) {
		String body = service.file(service.byCode(code), name);
		return body == null ? ResponseEntity.notFound().build() : text(body);
	}

	@GetMapping("/l/{code}/code.zip")
	ResponseEntity<byte[]> codeZip(@PathVariable String code) {
		return zip(service.byCode(code));
	}

	private ResponseEntity<byte[]> zip(StudentSession s) {
		byte[] zip = service.codeZip(s);
		return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
						.filename("course-" + s.getCourse().getId() + ".zip").build().toString())
				.body(zip);
	}

	private static ResponseEntity<String> text(String body) {
		return ResponseEntity.ok().contentType(TEXT).body(body);
	}

	@ExceptionHandler(InvalidPasteException.class)
	ResponseEntity<Map<String, String>> invalid(InvalidPasteException e) {
		return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
	}

	@ExceptionHandler(CodeUnavailableException.class)
	ResponseEntity<Map<String, String>> unavailable(CodeUnavailableException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
	}

	@ExceptionHandler(SessionNotFoundException.class)
	ResponseEntity<Map<String, String>> notFound() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", StudentCookie.NOT_FOUND_MESSAGE));
	}

	// PowerShell irm은 실패한 응답의 본문을 보여 준다. curl -f는 상태 코드만 보인다.
	@ExceptionHandler(DownloadCodeExpiredException.class)
	ResponseEntity<Map<String, String>> expired(DownloadCodeExpiredException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
	}
}
