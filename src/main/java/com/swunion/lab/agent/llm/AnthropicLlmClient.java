package com.swunion.lab.agent.llm;

import com.swunion.lab.agent.llm.LlmTypes.*;
import com.swunion.lab.config.ApiKeyResolver;
import com.swunion.lab.config.LabProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Anthropic Messages API 클라이언트. 재시도는 하지 않는다(에이전트 루프가 한 번 재시도). */
public class AnthropicLlmClient implements LlmClient {

	private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);
	static final String BASE_URL = "https://api.anthropic.com";
	// claude-haiku-5-5는 thinking 파라미터 없이도 adaptive thinking을 하고, thinking 토큰도 max_tokens에 든다.
	// 1024로는 설명(최대 1,200자)과 thinking을 함께 담기 어렵다. thinking을 끄지 않고 effort를 낮춰 양을 줄인다.
	static final int MAX_TOKENS = 4096;
	static final String EFFORT = "low";
	// effort를 모르는 모델(예: claude-haiku-4-5)은 output_config.effort가 있으면 400을 돌려 모든 실행이 실패한다.
	// Claude 5 계열만 보낸다. 다른 모델은 effort 없이 모델 기본값으로 돈다.
	static final List<String> EFFORT_MODEL_PREFIXES = List.of("claude-haiku-5", "claude-sonnet-5", "claude-opus-5",
			"claude-fable-5");

	private final RestClient rest;
	private final ApiKeyResolver keys;
	private final LabProperties props;
	private final JsonMapper json = JsonMapper.builder().build();

	public AnthropicLlmClient(RestClient rest, ApiKeyResolver keys, LabProperties props) {
		this.rest = rest;
		this.keys = keys;
		this.props = props;
	}

	/** 연결 10초, 읽기 60초 타임아웃을 건 RestClient. 테스트는 이 팩토리 없이 Builder에 Mock 서버를 바인딩한다. */
	public static RestClient newRestClient(RestClient.Builder builder) {
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(Duration.ofSeconds(60));
		return builder.requestFactory(factory).build();
	}

	@Override
	public LlmResponse send(String system, List<Turn> turns, List<ToolSpec> tools) {
		// 호출 시점에 읽어서 서버 실행 중에 .env에 키를 추가해도 동작한다.
		String key = keys.resolve().orElseThrow(() -> new LlmException("API 키 없음"));
		Map<String, Object> body = buildBody(system, turns, tools);
		String raw;
		try {
			raw = rest.post().uri(BASE_URL + "/v1/messages").contentType(MediaType.APPLICATION_JSON)
					.header("x-api-key", key).header("anthropic-version", "2023-06-01").body(body).retrieve()
					.body(String.class);
		} catch (RestClientResponseException e) {
			throw new LlmException(describeHttpError(e));
		} catch (RestClientException e) {
			// 원인 예외에 요청 정보가 섞일 수 있어 클래스명만 남기고 cause는 붙이지 않는다.
			throw new LlmException("LLM 호출 실패: " + e.getClass().getSimpleName());
		}
		log.debug("LLM 응답 수신 model={}", props.llmModel());
		return parse(raw);
	}

	private Map<String, Object> buildBody(String system, List<Turn> turns, List<ToolSpec> tools) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", props.llmModel());
		body.put("max_tokens", MAX_TOKENS);
		if (supportsEffort(props.llmModel())) {
			body.put("output_config", Map.of("effort", EFFORT));
		}
		if (system != null && !system.isEmpty()) {
			body.put("system", system);
		}
		List<Map<String, Object>> messages = new ArrayList<>();
		for (Turn turn : turns) {
			messages.add(toMessage(turn));
		}
		body.put("messages", messages);
		if (tools != null && !tools.isEmpty()) {
			List<Map<String, Object>> specs = new ArrayList<>();
			for (ToolSpec t : tools) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("name", t.name());
				m.put("description", t.description());
				m.put("input_schema", t.inputSchema());
				specs.add(m);
			}
			body.put("tools", specs);
		}
		return body;
	}

	static boolean supportsEffort(String model) {
		return model != null && EFFORT_MODEL_PREFIXES.stream().anyMatch(model::startsWith);
	}

	private Map<String, Object> toMessage(Turn turn) {
		Map<String, Object> msg = new LinkedHashMap<>();
		switch (turn) {
			case UserText u -> {
				msg.put("role", "user");
				msg.put("content", u.text());
			}
			case AssistantTurn a when a.providerContent() != null -> {
				// 받은 그대로(thinking·서명·순서 유지) 돌려보낸다. 다시 만들면 thinking 블록이 빠진다.
				msg.put("role", "assistant");
				msg.put("content", a.providerContent());
			}
			case AssistantTurn a -> {
				msg.put("role", "assistant");
				List<Map<String, Object>> blocks = new ArrayList<>();
				if (a.text() != null && !a.text().isEmpty()) {
					blocks.add(Map.of("type", "text", "text", a.text()));
				}
				for (ToolCall c : a.calls()) {
					Map<String, Object> b = new LinkedHashMap<>();
					b.put("type", "tool_use");
					b.put("id", c.id());
					b.put("name", c.name());
					b.put("input", c.input());
					blocks.add(b);
				}
				msg.put("content", blocks);
			}
			case ToolResults r -> {
				msg.put("role", "user");
				List<Map<String, Object>> blocks = new ArrayList<>();
				for (ToolResult t : r.results()) {
					Map<String, Object> b = new LinkedHashMap<>();
					b.put("type", "tool_result");
					b.put("tool_use_id", t.toolCallId());
					b.put("content", t.content());
					b.put("is_error", t.isError());
					blocks.add(b);
				}
				msg.put("content", blocks);
			}
		}
		return msg;
	}

	@SuppressWarnings("unchecked")
	private LlmResponse parse(String raw) {
		try {
			JsonNode root = json.readTree(raw);
			JsonNode content = root.path("content");
			if (!content.isArray()) {
				throw new LlmException("LLM 응답 형식 오류");
			}
			StringBuilder text = new StringBuilder();
			List<ToolCall> calls = new ArrayList<>();
			for (JsonNode block : content) {
				String type = block.path("type").asString("");
				if (type.equals("text")) {
					text.append(block.path("text").asString(""));
				} else if (type.equals("tool_use")) {
					Map<String, Object> input = json.convertValue(block.path("input"), Map.class);
					calls.add(new ToolCall(block.path("id").asString(""), block.path("name").asString(""),
							input == null ? Map.of() : input));
				}
			}
			String stop = root.path("stop_reason").isString() ? root.path("stop_reason").asString() : null;
			// Jackson 버전과 무관하게 직렬화되도록 JsonNode가 아니라 순수 List/Map으로 바꿔 둔다.
			List<Object> original = json.convertValue(content, List.class);
			return new LlmResponse(text.toString(), calls, stop, original);
		} catch (LlmException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new LlmException("LLM 응답 파싱 실패");
		}
	}

	private String describeHttpError(RestClientResponseException e) {
		String msg = "LLM HTTP " + e.getStatusCode().value();
		try {
			JsonNode type = json.readTree(e.getResponseBodyAsString()).path("error").path("type");
			if (type.isString()) {
				msg += " (" + type.asString() + ")";
			}
		} catch (RuntimeException ignored) {
			// 본문이 JSON이 아니면 상태 코드만 알린다.
		}
		return msg;
	}
}
