package com.swunion.lab.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.swunion.lab.agent.llm.LlmTypes.*;
import com.swunion.lab.config.ApiKeyResolver;
import com.swunion.lab.config.LabProperties;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** 실제 API 호출 1회. 키는 ApiKeyResolver가 프로젝트 .env/환경변수에서 읽는다(출력하지 않음). */
@Tag("llm")
class AnthropicLiveTest {

	@Test
	void modelCallsPingTool() {
		LabProperties props = new LabProperties("i", "p", "claude-haiku-5-5", 60, "http://x", "127.0.0.1",
				Path.of("./data"), "b", "c", "swunion-s", "http://host.docker.internal:8080");
		AnthropicLlmClient client = new AnthropicLlmClient(AnthropicLlmClient.newRestClient(RestClient.builder()),
				new ApiKeyResolver(), props);

		LlmResponse r = client.send("당신은 도구를 사용하는 에이전트다.", List.of(new UserText("ping 도구를 호출하라")),
				List.of(new ToolSpec("ping", "연결 확인용 도구", Map.of("type", "object", "properties", Map.of()))));

		assertThat(r.calls()).extracting(ToolCall::name).contains("ping");
	}
}
