package com.swunion.lab.local;

import com.swunion.lab.session.SessionNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LocalController {

	public record DiagnosticsBody(String os, String text) {
	}

	public record VerifyBody(String text) {
	}

	private static final MediaType TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);

	private final LocalService service;

	public LocalController(LocalService service) {
		this.service = service;
	}

	@GetMapping("/api/s/{token}/local/install")
	Map<String, String> install(@PathVariable String token, @RequestParam String os) {
		return service.installInfo(token, os);
	}

	@PostMapping("/api/s/{token}/local/diagnostics")
	ResponseEntity<Void> diagnostics(@PathVariable String token, @RequestBody DiagnosticsBody body) {
		service.saveDiagnostics(token, body.os(), body.text());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/api/s/{token}/local/verify")
	Map<String, Object> verify(@PathVariable String token, @RequestBody VerifyBody body) {
		return service.verify(token, body.text());
	}

	@GetMapping("/l/{token}/install.sh")
	ResponseEntity<String> installSh(@PathVariable String token) {
		return text(service.installSh(token));
	}

	@GetMapping("/l/{token}/install.ps1")
	ResponseEntity<String> installPs1(@PathVariable String token) {
		return text(service.installPs1(token));
	}

	@GetMapping("/l/{token}/files/{name}")
	ResponseEntity<String> file(@PathVariable String token, @PathVariable String name) {
		String body = service.file(token, name);
		return body == null ? ResponseEntity.notFound().build() : text(body);
	}

	@GetMapping("/l/{token}/code.zip")
	ResponseEntity<byte[]> codeZip(@PathVariable String token) {
		byte[] zip = service.codeZip(token);
		return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
						.filename("course-" + service.courseOf(token).getId() + ".zip").build().toString())
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
	ResponseEntity<Void> notFound() {
		return ResponseEntity.notFound().build();
	}
}
