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

	/**
	 * providerContent는 공급자 응답의 content 원본(불투명). 있으면 클라이언트가 그대로 다시 보낸다.
	 * thinking 블록과 서명을 바꾸지 않고 돌려줘야 모델이 도구 호출 사이에 추론을 이어 간다.
	 */
	public record AssistantTurn(String text, List<ToolCall> calls, Object providerContent) implements Turn {
		public AssistantTurn(String text, List<ToolCall> calls) {
			this(text, calls, null);
		}
	}

	public record ToolResults(List<ToolResult> results) implements Turn {
	}

	/** providerContent: 응답 content 원본. 에이전트 루프는 해석하지 않고 다음 AssistantTurn에 그대로 넘긴다. */
	public record LlmResponse(String text, List<ToolCall> calls, String stopReason, Object providerContent) {
		public LlmResponse(String text, List<ToolCall> calls, String stopReason) {
			this(text, calls, stopReason, null);
		}
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
