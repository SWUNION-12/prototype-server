package com.swunion.lab.auth;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// 로그인(POST /api/auth/login)과 로그아웃(POST /api/auth/logout)은 Spring Security 필터가 처리한다(SecurityConfig).
@RestController
class AuthController {

	// 로그인 화면은 이것으로 이미 로그인했는지 확인하고, 첫 응답에서 CSRF 쿠키도 받는다. 로그인 전 401은 보안 설정이 보낸다.
	@GetMapping("/api/auth/me")
	Map<String, String> me(Authentication authentication) {
		return Map.of("username", authentication.getName());
	}
}
