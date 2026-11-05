package com.swunion.lab.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swunion.lab.agent.llm.LlmTypes.*;
import com.swunion.lab.config.LabProperties;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 네트워크 없이 BedrockRuntimeClient를 가짜로 바꿔 요청 본문과 응답 해석을 확인한다. */
class BedrockLlmClientTest {

	static final String HAIKU_4_5 = "global.anthropic.claude-haiku-4-5-20251001-v1:0";
	static final JsonMapper JSON = JsonMapper.builder().build();

	static LabProperties props(String model) {
		return new LabProperties("i", "p", model, 60, "http://x", "127.0.0.1", Path.of("./data"), "b", "c",
				"swunion-s", "http://host.docker.internal:8080");
	}

	static InvokeModelResponse reply(String body) {
		return InvokeModelResponse.builder().body(SdkBytes.fromUtf8String(body)).build();
	}

	private static final List<ToolSpec> TOOLS = List
			.of(new ToolSpec("check_environment", "d", Map.of("type", "object", "properties", Map.of())));

	/** 한 번 호출하고 SDK에 넘어간 요청을 돌려준다. */
	private static InvokeModelRequest sent(String model, String response) {
		BedrockRuntimeClient sdk = mock(BedrockRuntimeClient.class);
		when(sdk.invokeModel(any(InvokeModelRequest.class))).thenReturn(reply(response));
		new BedrockLlmClient(sdk, props(model)).send("sys", List.of(new UserText("안녕")), TOOLS);
		ArgumentCaptor<InvokeModelRequest> captor = ArgumentCaptor.forClass(InvokeModelRequest.class);
		verify(sdk).invokeModel(captor.capture());
		return captor.getValue();
	}

	private static JsonNode bodyOf(InvokeModelRequest request) {
		return JSON.readTree(request.body().asUtf8String());
	}

	@Test
	void bodyHasNoModelButAnthropicVersionAndKeepsToolsAndMessages() {
		InvokeModelRequest request = sent(HAIKU_4_5, "{\"content\":[],\"stop_reason\":\"end_turn\"}");

		// 모델은 본문이 아니라 modelId로 간다.
		assertThat(request.modelId()).isEqualTo(HAIKU_4_5);
		assertThat(request.contentType()).isEqualTo("application/json");
		JsonNode body = bodyOf(request);
		assertThat(body.has("model")).isFalse();
		assertThat(body.path("anthropic_version").asString()).isEqualTo("bedrock-2023-05-31");
		assertThat(body.path("max_tokens").asInt()).isEqualTo(4096);
		assertThat(body.path("system").asString()).isEqualTo("sys");
		assertThat(body.path("tools").get(0).path("name").asString()).isEqualTo("check_environment");
		assertThat(body.path("tools").get(0).path("input_schema").path("type").asString()).isEqualTo("object");
		assertThat(body.path("messages").get(0).path("role").asString()).isEqualTo("user");
		assertThat(body.path("messages").get(0).path("content").asString()).isEqualTo("안녕");
	}

	@Test
	void effortIsNotSentForHaiku45BedrockId() {
		for (String model : List.of(HAIKU_4_5, "us.anthropic.claude-sonnet-4-5-20250929-v1:0",
				"anthropic.claude-3-haiku-20240307-v1:0", "claude-haiku-4-5")) {
			JsonNode body = bodyOf(sent(model, "{\"content\":[]}"));
			assertThat(body.has("output_config")).as(model).isFalse();
		}
	}

	@Test
	void effortRuleIgnoresBedrockPrefixForClaude5() {
		assertThat(MessagesProtocol.supportsEffort("global.anthropic.claude-haiku-5-5")).isTrue();
		assertThat(MessagesProtocol.supportsEffort("us.anthropic.claude-sonnet-5-1")).isTrue();
		assertThat(MessagesProtocol.supportsEffort("claude-haiku-5-5")).isTrue();
		assertThat(MessagesProtocol.supportsEffort("global.anthropic.claude-haiku-4-5-20251001-v1:0")).isFalse();
		assertThat(MessagesProtocol.supportsEffort("my-proxy-model")).isFalse();
		assertThat(MessagesProtocol.supportsEffort(null)).isFalse();
	}

	@Test
	void parsesToolUseResponseWithSharedParser() {
		BedrockRuntimeClient sdk = mock(BedrockRuntimeClient.class);
		when(sdk.invokeModel(any(InvokeModelRequest.class))).thenReturn(reply("""
				{"content":[{"type":"text","text":"확인"},{"type":"text","text":"합니다"},
				{"type":"tool_use","id":"t1","name":"check_environment","input":{"n":1}}],"stop_reason":"tool_use"}
				"""));

		LlmResponse r = new BedrockLlmClient(sdk, props(HAIKU_4_5)).send("s", List.of(new UserText("a")), TOOLS);

		assertThat(r.text()).isEqualTo("확인합니다");
		assertThat(r.calls()).containsExactly(new ToolCall("t1", "check_environment", Map.of("n", 1)));
		assertThat(r.stopReason()).isEqualTo("tool_use");
		assertThat(r.providerContent()).isInstanceOf(List.class);
	}

	@Test
	void sharedParserIsTheSameForBothProviders() {
		String raw = "{\"content\":[{\"type\":\"text\",\"text\":\"x\"}],\"stop_reason\":\"end_turn\"}";
		BedrockRuntimeClient sdk = mock(BedrockRuntimeClient.class);
		when(sdk.invokeModel(any(InvokeModelRequest.class))).thenReturn(reply(raw));
		assertThat(new BedrockLlmClient(sdk, props(HAIKU_4_5)).send("s", List.of(new UserText("a")), List.of()))
				.isEqualTo(MessagesProtocol.parse(raw));
	}

	@Test
	void serviceErrorBecomesLlmExceptionWithStatusAndCodeOnly() {
		AwsServiceException denied = AwsServiceException.builder().statusCode(403)
				.message("User: arn:aws:sts::1234:assumed-role/secret-role is not authorized")
				.awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDeniedException").build()).build();
		BedrockRuntimeClient sdk = mock(BedrockRuntimeClient.class);
		when(sdk.invokeModel(any(InvokeModelRequest.class))).thenThrow(denied);

		assertThatThrownBy(() -> new BedrockLlmClient(sdk, props(HAIKU_4_5)).send("s", List.of(new UserText("a")), List.of()))
				.isInstanceOf(LlmException.class).hasMessageContaining("403")
				.hasMessageContaining("AccessDeniedException").hasMessageNotContaining("secret-role")
				.hasNoCause();
	}

	@Test
	void clientErrorsLikeMissingCredentialsOrTimeoutBecomeLlmException() {
		BedrockRuntimeClient sdk = mock(BedrockRuntimeClient.class);
		when(sdk.invokeModel(any(InvokeModelRequest.class)))
				.thenThrow(SdkClientException.create("Unable to load credentials from any of the providers"));

		assertThatThrownBy(() -> new BedrockLlmClient(sdk, props(HAIKU_4_5)).send("s", List.of(new UserText("a")), List.of()))
				.isInstanceOf(LlmException.class).hasMessageContaining("SdkClientException").hasNoCause();
	}

	@Test
	void unparseableBodyBecomesLlmException() {
		BedrockRuntimeClient sdk = mock(BedrockRuntimeClient.class);
		when(sdk.invokeModel(any(InvokeModelRequest.class))).thenReturn(reply("not json"));

		assertThatThrownBy(() -> new BedrockLlmClient(sdk, props(HAIKU_4_5)).send("s", List.of(new UserText("a")), List.of()))
				.isInstanceOf(LlmException.class);
	}

	@Test
	void runtimeClientFactoryDoesNotNeedCredentialsOrNetwork() {
		// 로컬 Mac에는 AWS 자격 증명이 없다. 클라이언트를 만드는 것만으로는 실패하지 않아야 한다.
		try (BedrockRuntimeClient c = BedrockLlmClient.newRuntimeClient("ap-southeast-1")) {
			assertThat(c).isNotNull();
		}
	}
}
