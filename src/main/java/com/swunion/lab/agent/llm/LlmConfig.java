package com.swunion.lab.agent.llm;

import com.swunion.lab.config.ApiKeyResolver;
import com.swunion.lab.config.LabProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
class LlmConfig {

	static final String ANTHROPIC = "anthropic";
	static final String BEDROCK = "bedrock";

	// Boot 4에서 RestClient.Builder 빈은 별도 autoconfigure 모듈이라 starter-webmvc만으로는 없다. 직접 만든다.
	// 공급자 설정은 LabProperties 생성자를 늘리지 않으려고 여기서 읽는다. 어느 쪽도 API 키·AWS 자격 증명을 시작 때 요구하지 않는다(키는 호출 때 찾는다).
	@Bean
	LlmClient llmClient(ApiKeyResolver keys, LabProperties props, @Value("${lab.llm-provider:anthropic}") String provider,
			@Value("${lab.bedrock-region:ap-southeast-1}") String bedrockRegion) {
		return create(provider, bedrockRegion, keys, props);
	}

	/** 알 수 없는 공급자는 조용히 기본값으로 돌리지 않고 서버 시작을 막는다. 오타로 키 없는 서버가 뜨는 것을 막기 위해서다. */
	static LlmClient create(String provider, String bedrockRegion, ApiKeyResolver keys, LabProperties props) {
		// 빈 값(LLM_PROVIDER=)은 설정하지 않은 것과 같게 본다. env 파일에서 값만 지운 경우를 오타로 막지 않는다.
		String name = provider == null || provider.isBlank() ? ANTHROPIC : provider.strip();
		return switch (name) {
			case ANTHROPIC -> new AnthropicLlmClient(AnthropicLlmClient.newRestClient(RestClient.builder()), keys, props);
			case BEDROCK -> new BedrockLlmClient(BedrockLlmClient.newRuntimeClient(bedrockRegion), props);
			default -> throw new IllegalStateException(
					"lab.llm-provider는 anthropic 또는 bedrock이어야 합니다 (현재 값: " + provider + ")");
		};
	}
}
