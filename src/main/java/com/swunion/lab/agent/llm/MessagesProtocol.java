package com.swunion.lab.agent.llm;

import com.swunion.lab.agent.llm.LlmTypes.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anthropic Messages 형식의 요청 본문 만들기와 응답 해석. 직접 호출(AnthropicLlmClient)과 Bedrock
 * (BedrockLlmClient)이 같은 JSON을 쓰므로 한 곳에 둔다. 두 공급자의 차이는 호출 방식과 본문의
 * model/anthropic_version 필드뿐이라 그것만 각 클라이언트가 얹는다.
 */
final class MessagesProtocol {

	// claude-haiku-5-5는 thinking 파라미터 없이도 adaptive thinking을 하고, thinking 토큰도 max_tokens에 든다.
	// 1024로는 설명(최대 1,200자)과 thinking을 함께 담기 어렵다. thinking을 끄지 않고 effort를 낮춰 양을 줄인다.
	static final int MAX_TOKENS = 4096;
	static final String EFFORT = "low";
	// effort를 모르는 모델(예: claude-haiku-4-5)은 output_config.effort가 있으면 400을 돌려 모든 실행이 실패한다.
	// Claude 5 계열만 보낸다. 다른 모델은 effort 없이 모델 기본값으로 돈다.
	static final List<String> EFFORT_MODEL_PREFIXES = List.of("claude-haiku-5", "claude-sonnet-5", "claude-opus-5",
			"claude-fable-5");

	private static final JsonMapper json = JsonMapper.builder().build();

	private MessagesProtocol() {
	}

	/** model 필드는 넣지 않는다. 직접 호출은 본문에, Bedrock은 요청 경로에 모델을 싣기 때문이다. */
	static Map<String, Object> buildBody(String model, String system, List<Turn> turns, List<ToolSpec> tools) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("max_tokens", MAX_TOKENS);
		if (supportsEffort(model)) {
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

	/**
	 * Bedrock id는 "global.anthropic.claude-…", "us.anthropic.claude-…", "anthropic.claude-…"처럼 앞에 접두사가 붙는다.
	 * 접두사를 떼고 같은 규칙으로 판단한다. haiku-4-5처럼 Claude 5가 아니면 effort를 보내지 않는다.
	 */
	static boolean supportsEffort(String model) {
		if (model == null) {
			return false;
		}
		int vendor = model.indexOf("anthropic.");
		String name = vendor >= 0 ? model.substring(vendor + "anthropic.".length()) : model;
		return EFFORT_MODEL_PREFIXES.stream().anyMatch(name::startsWith);
	}

	private static Map<String, Object> toMessage(Turn turn) {
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
	static LlmResponse parse(String raw) {
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
}
