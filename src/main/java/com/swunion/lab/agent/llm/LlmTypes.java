package com.swunion.lab.agent.llm;

import java.util.List;
import java.util.Map;

/** 공급자 중립 LLM 타입 묶음. 에이전트 루프는 이 타입만 알고 Anthropic JSON 형식은 모른다. */
public final class LlmTypes {

	private LlmTypes() {
	}

	public record ToolSpec(String name, String description, Map<String, Object> inputSchema) {
	}

	public record ToolCall(String id, String name, Map<String, Object> input) {
	}

	public record ToolResult(String toolCallId, String content, boolean isError) {
	}

	public sealed interface Turn permits UserText, AssistantTurn, ToolResults {
	}

	public record UserText(String text) implements Turn {
	}

	public record AssistantTurn(String text, List<ToolCall> calls) implements Turn {
	}

	public record ToolResults(List<ToolResult> results) implements Turn {
	}

	public record LlmResponse(String text, List<ToolCall> calls, String stopReason) {
	}

	/** 메시지에는 키·요청 본문·헤더를 절대 넣지 않는다. */
	public static class LlmException extends RuntimeException {
		public LlmException(String message) {
			super(message);
		}

		public LlmException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
