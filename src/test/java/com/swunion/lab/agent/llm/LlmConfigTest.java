package com.swunion.lab.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.swunion.lab.config.ApiKeyResolver;
import com.swunion.lab.config.LabProperties;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class LlmConfigTest {

	// API 키가 하나도 없는 환경(운영 서버처럼 .env가 없고 환경변수도 없는 상태)
	private static final ApiKeyResolver NO_KEY = new ApiKeyResolver(Map.of(), Path.of("build/no-such.env"));
	private static final LabProperties PROPS = new LabProperties("i", "p", "m", 60, "http://x", "127.0.0.1",
			Path.of("./data"), "b", "c", "swunion-s", "http://host.docker.internal:8080");

	@Configuration
	@Import(LlmConfig.class)
	static class Deps {
		@Bean
		ApiKeyResolver keys() {
			return NO_KEY;
		}

		@Bean
		LabProperties props() {
			return PROPS;
		}
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Deps.class);

	@Test
	void defaultsToAnthropic() {
		runner.run(ctx -> assertThat(ctx.getBean(LlmClient.class)).isInstanceOf(AnthropicLlmClient.class));
	}

	@Test
	void anthropicProviderSelectsAnthropicClient() {
		runner.withPropertyValues("lab.llm-provider=anthropic")
				.run(ctx -> assertThat(ctx.getBean(LlmClient.class)).isInstanceOf(AnthropicLlmClient.class));
	}

	@Test
	void bedrockProviderSelectsBedrockClientWithoutApiKeyOrCredentials() {
		runner.withPropertyValues("lab.llm-provider=bedrock", "lab.bedrock-region=ap-southeast-1")
				.run(ctx -> assertThat(ctx.getBean(LlmClient.class)).isInstanceOf(BedrockLlmClient.class));
	}

	@Test
	void unknownProviderFailsStartup() {
		runner.withPropertyValues("lab.llm-provider=openai").run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(ctx.getStartupFailure()).hasStackTraceContaining("anthropic 또는 bedrock");
		});
	}

	@Test
	void blankProviderMeansDefault() {
		assertThat(LlmConfig.create("", "ap-southeast-1", NO_KEY, PROPS)).isInstanceOf(AnthropicLlmClient.class);
		assertThat(LlmConfig.create(null, "ap-southeast-1", NO_KEY, PROPS)).isInstanceOf(AnthropicLlmClient.class);
	}

	@Test
	void providerNamesAreExactAndTrimmedOnly() {
		assertThat(LlmConfig.create(" bedrock ", "ap-southeast-1", NO_KEY, PROPS)).isInstanceOf(BedrockLlmClient.class);
		for (String bad : new String[] { "Bedrock", "aws", "openai" }) {
			assertThatThrownBy(() -> LlmConfig.create(bad, "ap-southeast-1", NO_KEY, PROPS))
					.isInstanceOf(IllegalStateException.class);
		}
	}
}
