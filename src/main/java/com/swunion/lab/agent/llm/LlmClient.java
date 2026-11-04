package com.swunion.lab.agent.llm;

import com.swunion.lab.agent.llm.LlmTypes.LlmResponse;
import com.swunion.lab.agent.llm.LlmTypes.ToolSpec;
import com.swunion.lab.agent.llm.LlmTypes.Turn;
import java.util.List;

public interface LlmClient {

	LlmResponse send(String system, List<Turn> turns, List<ToolSpec> tools);
}
