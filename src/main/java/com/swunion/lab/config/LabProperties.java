package com.swunion.lab.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("lab")
public record LabProperties(
		String instructorUsername,
		String instructorPassword,
		String llmModel,
		int checkIntervalSeconds,
		String publicBaseUrl,
		String ideBindAddress,
		@DefaultValue("./data") Path dataDir,
		@DefaultValue("swunion/lab-base:1") String baseImage,
		@DefaultValue("swunion/course-") String courseImagePrefix,
		// 컨테이너·볼륨 이름 접두사. 테스트는 실서버 학생 볼륨(swunion-s1 등)과 겹치지 않게 다른 값을 쓴다.
		@DefaultValue("swunion-s") String containerNamePrefix) {
}
