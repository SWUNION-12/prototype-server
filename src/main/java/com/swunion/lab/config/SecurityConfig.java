package com.swunion.lab.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.util.pattern.PathPatternParser;

@Configuration
public class SecurityConfig {

	private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
	static final String DEFAULT_PASSWORD = "instructor";

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	@Bean
	UserDetailsService userDetailsService(LabProperties props, PasswordEncoder encoder) {
		// 학생 컨테이너는 host.docker.internal:8080으로 서버에 닿는다. 기본 비밀번호면 학생이 강사 API를 쓸 수 있다.
		if (DEFAULT_PASSWORD.equals(props.instructorPassword())) {
			log.warn("강사 비밀번호가 기본값입니다. 학생에게 IDE를 주기 전에 INSTRUCTOR_PASSWORD를 바꾸세요");
		}
		return new InMemoryUserDetailsManager(User.withUsername(props.instructorUsername())
				.password(encoder.encode(props.instructorPassword()))
				.roles("INSTRUCTOR")
				.build());
	}

	// 학생은 세션 토큰 경로(/api/s/{token}, /l/{token})로, 학생 컨테이너 안의 확장은 컨테이너 키 경로(/api/ide/**,
	// 헤더 X-Session-Key)로만 접근하므로 강사 영역만 인증한다. 토큰·키 기반 POST가 많아 CSRF는 끈다.
	// 강사 화면은 정적 빌드의 admin.html, admin.txt(RSC), admin/ 아래 파일이다. /admin/**는 /admin 자체를, /admin.*/**는
	// 리소스 핸들러가 끝 슬래시를 떼고 찾는 /admin.html/ 같은 주소까지 덮는다. 로컬 실행(압축 풀린 classpath)은 macOS에서
	// 대소문자를 가리지 않아 /ADMIN.txt도 admin.txt로 열리므로 규칙도 대소문자를 가리지 않는다.
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		PathPatternParser parser = new PathPatternParser();
		parser.setCaseSensitive(false);
		PathPatternRequestMatcher.Builder path = PathPatternRequestMatcher.withPathPatternParser(parser);
		http.authorizeHttpRequests(a -> a
						.requestMatchers(path.matcher("/api/courses/**"), path.matcher("/admin/**"),
								path.matcher("/admin.*/**"), path.matcher("/instructor.html"), path.matcher("/dashboard.html"))
						.authenticated()
						.anyRequest().permitAll())
				.csrf(AbstractHttpConfigurer::disable)
				.httpBasic(Customizer.withDefaults());
		return http.build();
	}
}
