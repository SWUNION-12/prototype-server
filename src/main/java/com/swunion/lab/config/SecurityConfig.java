package com.swunion.lab.config;

import com.swunion.lab.auth.AuthResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
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

	// 대소문자를 가리지 않는 경로 매처. 로컬 실행(압축 풀린 classpath)은 macOS에서 대소문자를 가리지 않아 /INSTRUCTOR.txt도
	// instructor.txt로 열린다. Spring 7의 경로 패턴은 끝 슬래시를 따로 맞추지 않으므로 /x와 /x/는 다른 주소다.
	public static PathPatternRequestMatcher.Builder paths() {
		PathPatternParser parser = new PathPatternParser();
		parser.setCaseSensitive(false);
		return PathPatternRequestMatcher.withPathPatternParser(parser);
	}

	// 강사는 로그인 화면에서 세션으로 로그인한다(HTTP Basic 끔). 강사 화면은 정적 빌드의 instructor.html, instructor.txt(RSC),
	// instructor/ 아래 파일이다. /instructor/**는 /instructor 자체를, /instructor.*/**는 리소스 핸들러가 끝 슬래시를 떼고
	// 찾는 /instructor.html/ 같은 주소까지 덮는다. 그보다 먼저 로그인 화면 파일(/instructor/login, login.html·login.txt,
	// login/ 아래 RSC 조각)만 연다. 패턴이 끝 슬래시를 맞추지 않으므로 /instructor/login.html/ 같은 주소는 로그인이 필요하다.
	// 학생은 세션 토큰 경로(/api/join, /api/s/{token}, /l/{token})로, 학생 컨테이너 안의 확장은 컨테이너 키(/api/ide/**,
	// 헤더 X-Session-Key)로만 접근하고 쿠키 로그인을 쓰지 않으므로 CSRF 검사에서 뺀다. 강사 요청과 로그인·로그아웃은
	// XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 보내야 한다.
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, AuthResponses responses) throws Exception {
		PathPatternRequestMatcher.Builder path = paths();
		http.authorizeHttpRequests(a -> a
						.requestMatchers(path.matcher("/instructor/login"), path.matcher("/instructor/login.*"),
								path.matcher("/instructor/login/**"))
						.permitAll()
						.requestMatchers(path.matcher("/api/courses/**"), path.matcher("/api/auth/me"),
								path.matcher("/instructor/**"), path.matcher("/instructor.*/**"))
						.authenticated()
						.anyRequest().permitAll())
				// spa(): 쿠키가 없는 요청의 응답에 HttpOnly가 아닌 XSRF-TOKEN 쿠키를 심고(화면이 처음 열릴 때 생긴다),
				// 헤더로 온 값을 쿠키 값과 그대로 비교한다. 쿠키는 https 요청일 때만 Secure다.
				.csrf(c -> c.spa().ignoringRequestMatchers(path.matcher("/api/join"), path.matcher("/api/s/**"),
						path.matcher("/l/**"), path.matcher("/api/ide/**")))
				.httpBasic(AbstractHttpConfigurer::disable)
				// 로그인 화면을 따로 정해 두어야 Spring Security 기본 로그인 화면(/login)이 생기지 않는다.
				// 로그인하면 세션 ID를 바꾸고 CSRF 값을 새로 만든다(Spring Security 기본 동작).
				.formLogin(f -> f.loginPage(AuthResponses.LOGIN_PAGE)
						.loginProcessingUrl("/api/auth/login")
						.successHandler(responses)
						.failureHandler(responses))
				.logout(l -> l.logoutUrl("/api/auth/logout")
						.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
						.deleteCookies("JSESSIONID"))
				.exceptionHandling(e -> e.authenticationEntryPoint(responses).accessDeniedHandler(responses))
				// 돌아갈 주소는 next로 넘긴다. 로그인 전 요청을 세션에 저장하지 않아 로그인 전에는 세션이 생기지 않는다.
				.requestCache(r -> r.requestCache(new NullRequestCache()));
		return http.build();
	}
}
