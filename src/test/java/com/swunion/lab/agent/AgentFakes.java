package com.swunion.lab.agent;

import com.swunion.lab.agent.llm.LlmClient;
import com.swunion.lab.agent.llm.LlmTypes.LlmResponse;
import com.swunion.lab.agent.llm.LlmTypes.ToolCall;
import com.swunion.lab.agent.llm.LlmTypes.ToolSpec;
import com.swunion.lab.agent.llm.LlmTypes.Turn;
import com.swunion.lab.check.CheckResult;
import com.swunion.lab.check.EnvironmentChecker;
import com.swunion.lab.check.Issue;
import com.swunion.lab.check.IssueType;
import com.swunion.lab.docker.ContainerSpec;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.docker.ExecResult;
import com.swunion.lab.session.StudentSession;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 에이전트 단위 테스트용 가짜 LLM·점검기·Docker. */
final class AgentFakes {

	static final CheckResult OK = new CheckResult(true, false, List.of(), List.of());
	static final CheckResult MISSING_SIX = new CheckResult(true, false,
			List.of(new Issue(IssueType.MISSING, "six", "1.16.0", null)), List.of());

	private AgentFakes() {
	}

	static ToolCall call(String id, String name, Map<String, Object> input) {
		return new ToolCall(id, name, input);
	}

	static LlmResponse calls(ToolCall... calls) {
		return new LlmResponse("", List.of(calls), "tool_use");
	}

	static LlmResponse finish(String id, String classification, String summary) {
		return calls(call(id, "finish", Map.of("classification", classification, "summary", summary)));
	}

	record ExecCall(String containerId, List<String> cmd, String workDir, Duration timeout) {
	}

	static class FakeGateway implements DockerGateway {
		final List<ExecCall> execs = new ArrayList<>();
		final List<String> restarted = new ArrayList<>();
		ExecResult execResult = new ExecResult(0, "", "Installed 1 package");
		RuntimeException execFailure;

		@Override
		public ExecResult exec(String id, List<String> cmd, Duration timeout) {
			return exec(id, cmd, null, timeout);
		}

		@Override
		public ExecResult exec(String id, List<String> cmd, String workDir, Duration timeout) {
			execs.add(new ExecCall(id, List.copyOf(cmd), workDir, timeout));
			if (execFailure != null) {
				throw execFailure;
			}
			return execResult;
		}

		@Override
		public void restart(String id) {
			restarted.add(id);
		}

		@Override
		public int hostPort(String id, int containerPort) {
			return 41000;
		}

		@Override
		public boolean isRunning(String id) {
			return true;
		}

		@Override
		public boolean imageExists(String image) {
			return true;
		}

		@Override
		public String buildImage(Path contextDir, String tag, Consumer<String> log) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ExecResult runOnce(String image, List<String> cmd, Duration timeout) {
			throw new UnsupportedOperationException();
		}

		@Override
		public String createAndStart(ContainerSpec spec) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void stop(String id) {
		}

		@Override
		public void remove(String id) {
		}

		@Override
		public void removeVolume(String volumeName) {
		}

		@Override
		public InputStream copyArchive(String id, String path) {
			throw new UnsupportedOperationException();
		}
	}

	/** check()는 current를, checkAndRecord()는 rechecks를 순서대로(비면 current) 돌려준다. */
	static class FakeChecker extends EnvironmentChecker {
		CheckResult current = OK;
		final Deque<CheckResult> rechecks = new ArrayDeque<>();
		int checkCount;
		int recordCount;
		Runnable onRecord = () -> {
		};

		FakeChecker() {
			super(null, null, null, null, null);
		}

		@Override
		public CheckResult check(StudentSession session) {
			checkCount++;
			return current;
		}

		@Override
		public CheckResult checkAndRecord(StudentSession session) {
			recordCount++;
			onRecord.run();
			return rechecks.isEmpty() ? current : rechecks.poll();
		}
	}

	/** script를 순서대로 소비한다. 원소가 RuntimeException이면 던진다. 비면 fallback. */
	static class FakeLlm implements LlmClient {
		final Deque<Object> script = new ArrayDeque<>();
		final List<List<Turn>> sent = new ArrayList<>();
		final List<String> systems = new ArrayList<>();
		final List<List<ToolSpec>> tools = new ArrayList<>();
		Supplier<LlmResponse> fallback = () -> {
			throw new AssertionError("예상하지 않은 LLM 호출");
		};

		FakeLlm then(Object... steps) {
			script.addAll(List.of(steps));
			return this;
		}

		@Override
		public LlmResponse send(String system, List<Turn> turns, List<ToolSpec> toolSpecs) {
			systems.add(system);
			sent.add(List.copyOf(turns));
			tools.add(toolSpecs);
			Object next = script.poll();
			if (next == null) {
				return fallback.get();
			}
			if (next instanceof RuntimeException e) {
				throw e;
			}
			return (LlmResponse) next;
		}
	}
}
