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

	// 레코드 기본 toString은 모든 값을 찍는다. 설정 객체가 로그나 예외 메시지에 실려도 강사 비밀번호가 남지 않게 가린다.
	@Override
	public String toString() {
		return "LabProperties[instructorUsername=" + instructorUsername + ", instructorPassword=***, llmModel=" + llmModel
				+ ", checkIntervalSeconds=" + checkIntervalSeconds + ", publicBaseUrl=" + publicBaseUrl
				+ ", ideBindAddress=" + ideBindAddress + ", dataDir=" + dataDir + ", baseImage=" + baseImage
				+ ", courseImagePrefix=" + courseImagePrefix + ", containerNamePrefix=" + containerNamePrefix + "]";
	}
}
