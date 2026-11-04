package com.swunion.lab.course;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class CourseExecutorConfig {

	// 이미지 빌드는 무겁기 때문에 동시에 두 개까지만 돌린다.
	@Bean("courseExecutor")
	TaskExecutor courseExecutor() {
		ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
		ex.setCorePoolSize(2);
		ex.setMaxPoolSize(2);
		ex.setQueueCapacity(50);
		ex.setThreadNamePrefix("course-provision-");
		return ex;
	}
}
