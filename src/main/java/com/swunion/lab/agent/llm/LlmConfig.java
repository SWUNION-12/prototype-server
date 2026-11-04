package com.swunion.lab.agent.llm;

import com.swunion.lab.config.ApiKeyResolver;
import com.swunion.lab.config.LabProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
class LlmConfig {

	@Bean
	// Boot 4에서 RestClient.Builder 빈은 별도 autoconfigure 모듈이라 starter-webmvc만으로는 없다. 직접 만든다.
	LlmClient llmClient(ApiKeyResolver keys, LabProperties props) {
		return new AnthropicLlmClient(AnthropicLlmClient.newRestClient(RestClient.builder()), keys, props);
	}
}
