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
		@DefaultValue("swunion/lab-base:1") String baseImage) {
}
