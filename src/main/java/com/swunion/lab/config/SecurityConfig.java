package com.swunion.lab.config;

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

@Configuration
public class SecurityConfig {

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	@Bean
	UserDetailsService userDetailsService(LabProperties props, PasswordEncoder encoder) {
		return new InMemoryUserDetailsManager(User.withUsername(props.instructorUsername())
				.password(encoder.encode(props.instructorPassword()))
				.roles("INSTRUCTOR")
				.build());
	}

	// 학생은 세션 토큰 경로(/api/s/{token}, /l/{token})로만 접근하므로 강사 영역만 인증한다.
	// 토큰 기반 POST가 많아 CSRF는 끈다.
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http.authorizeHttpRequests(a -> a
						.requestMatchers("/api/courses/**", "/instructor.html", "/dashboard.html")
						.authenticated()
						.anyRequest().permitAll())
				.csrf(AbstractHttpConfigurer::disable)
				.httpBasic(Customizer.withDefaults());
		return http.build();
	}
}
