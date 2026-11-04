package com.swunion.lab.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.docker.DockerJavaGateway;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DockerConfig {

	// 연결은 첫 호출 때 맺어지므로 Docker 없이도 컨텍스트가 뜬다.
	@Bean(destroyMethod = "close")
	public DockerClient dockerClient() {
		String env = System.getenv("DOCKER_HOST");
		String host = env == null || env.isBlank() ? "unix:///var/run/docker.sock" : env;
		// API 버전을 지정하지 않는다. Docker 29는 낮은 버전 고정을 거부한다.
		DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost(host)
				.build();
		var http = new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost())
				.sslConfig(config.getSSLConfig()).connectionTimeout(Duration.ofSeconds(10))
				.responseTimeout(Duration.ofMinutes(10)).build();
		return DockerClientImpl.getInstance(config, http);
	}

	@Bean
	public DockerGateway dockerGateway(DockerClient client) {
		return new DockerJavaGateway(client);
	}
}
