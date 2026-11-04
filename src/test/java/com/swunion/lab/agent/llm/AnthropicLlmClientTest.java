package com.swunion.lab.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.swunion.lab.agent.llm.LlmTypes.*;
import com.swunion.lab.config.ApiKeyResolver;
import com.swunion.lab.config.LabProperties;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;

class AnthropicLlmClientTest {

	static final String KEY = "sk-test-SECRETKEY123";

	MockRestServiceServer server;
	AnthropicLlmClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		client = build(builder, Map.of("ANTHROPIC_API_KEY", KEY));
	}

	static AnthropicLlmClient build(RestClient.Builder builder, Map<String, String> env) {
		LabProperties props = new LabProperties("i", "p", "claude-haiku-5-5", 60, "http://x", "127.0.0.1", Path.of("./data"),
				"b", "c", "swunion-s");
		return new AnthropicLlmClient(builder.build(), new ApiKeyResolver(env, Path.of("build/no-such.env")), props);
	}

	@Test
	void mapsToolUseResponse() {
		server.expect(requestTo("https://api.anthropic.com/v1/messages")).andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.model").value("claude-haiku-5-5")).andExpect(jsonPath("$.max_tokens").value(1024))
				.andExpect(jsonPath("$.system").value("sys"))
				.andExpect(jsonPath("$.tools[0].name").value("check_environment"))
				.andExpect(jsonPath("$.tools[0].input_schema.type").value("object"))
				.andExpect(jsonPath("$.messages[0].role").value("user"))
				.andExpect(jsonPath("$.messages[0].content").value("안녕"))
				.andRespond(withSuccess("""
						{"content":[{"type":"text","text":"확인"},{"type":"text","text":"합니다"},
						{"type":"tool_use","id":"t1","name":"check_environment","input":{}}],"stop_reason":"tool_use"}
						""", MediaType.APPLICATION_JSON));

		LlmResponse r = client.send("sys", List.of(new UserText("안녕")),
				List.of(new ToolSpec("check_environment", "d", Map.of("type", "object", "properties", Map.of()))));

		assertThat(r.text()).isEqualTo("확인합니다");
		assertThat(r.calls()).containsExactly(new ToolCall("t1", "check_environment", Map.of()));
		assertThat(r.stopReason()).isEqualTo("tool_use");
		server.verify();
	}

	@Test
	void sendsAssistantTurnAndToolResultsAsMessages() {
		server.expect(requestTo("https://api.anthropic.com/v1/messages"))
				.andExpect(jsonPath("$.messages[1].role").value("assistant"))
				.andExpect(jsonPath("$.messages[1].content[0].type").value("text"))
				.andExpect(jsonPath("$.messages[1].content[1].type").value("tool_use"))
				.andExpect(jsonPath("$.messages[1].content[1].id").value("t1"))
				.andExpect(jsonPath("$.messages[2].role").value("user"))
				.andExpect(jsonPath("$.messages[2].content[0].type").value("tool_result"))
				.andExpect(jsonPath("$.messages[2].content[0].tool_use_id").value("t1"))
				.andExpect(jsonPath("$.messages[2].content[0].content").value("결과"))
				.andExpect(jsonPath("$.messages[2].content[0].is_error").value(false))
				.andRespond(withSuccess("{\"content\":[{\"type\":\"text\",\"text\":\"끝\"}],\"stop_reason\":\"end_turn\"}",
						MediaType.APPLICATION_JSON));

		LlmResponse r = client.send("sys", List.of(new UserText("go"),
				new AssistantTurn("보자", List.of(new ToolCall("t1", "x", Map.of("a", 1)))),
				new ToolResults(List.of(new ToolResult("t1", "결과", false)))), List.of());

		assertThat(r.text()).isEqualTo("끝");
		assertThat(r.calls()).isEmpty();
		server.verify();
	}

	@Test
	void assistantTurnWithEmptyTextOmitsTextBlock() {
		server.expect(requestTo("https://api.anthropic.com/v1/messages"))
				.andExpect(jsonPath("$.messages[1].content[0].type").value("tool_use"))
				.andExpect(jsonPath("$.messages[1].content.length()").value(1))
				.andRespond(withSuccess("{\"content\":[],\"stop_reason\":\"end_turn\"}", MediaType.APPLICATION_JSON));
		client.send("s", List.of(new UserText("go"), new AssistantTurn("", List.of(new ToolCall("t1", "x", Map.of())))),
				List.of());
		server.verify();
	}

	@Test
	void http500BecomesLlmException() {
		server.expect(requestTo("https://api.anthropic.com/v1/messages")).andRespond(withServerError()
				.body("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"x\"}}")
				.contentType(MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> client.send("s", List.of(new UserText("a")), List.of()))
				.isInstanceOf(LlmException.class).hasMessageContaining("500").hasMessageContaining("overloaded_error")
				.hasMessageNotContaining(KEY);
	}

	@Test
	void unparseableBodyBecomesLlmException() {
		server.expect(requestTo("https://api.anthropic.com/v1/messages"))
				.andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> client.send("s", List.of(new UserText("a")), List.of()))
				.isInstanceOf(LlmException.class);
	}

	@Test
	void requestCarriesNoKeyInBodyAndUsesHeader() {
		server.expect(requestTo("https://api.anthropic.com/v1/messages")).andExpect(header("x-api-key", KEY))
				.andExpect(header("anthropic-version", "2023-06-01"))
				.andExpect(request -> assertThat(request.getBody().toString()).doesNotContain(KEY))
				.andRespond(withSuccess("{\"content\":[],\"stop_reason\":\"end_turn\"}", MediaType.APPLICATION_JSON));
		client.send("s", List.of(new UserText("a")), List.of());
		server.verify();
	}

	@Test
	void missingKeyFailsWithoutCallingApi() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer s = MockRestServiceServer.bindTo(builder).build();
		AnthropicLlmClient noKey = build(builder, Map.of());
		assertThatThrownBy(() -> noKey.send("s", List.of(new UserText("a")), List.of()))
				.isInstanceOf(LlmException.class).hasMessage("API 키 없음");
		s.verify();
	}
}
