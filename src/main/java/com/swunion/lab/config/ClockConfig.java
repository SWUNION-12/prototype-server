package com.swunion.lab.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

	// 시간에 기대는 코드가 Clock을 주입받으면 테스트가 시각을 고정할 수 있다.
	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}
}
