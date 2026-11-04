package com.swunion.lab.agent;

import static com.swunion.lab.agent.AgentFakes.MISSING_SIX;
import static com.swunion.lab.agent.AgentFakes.OK;
import static com.swunion.lab.agent.AgentFakes.call;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.swunion.lab.agent.AgentFakes.ExecCall;
import com.swunion.lab.agent.AgentFakes.FakeChecker;
import com.swunion.lab.agent.AgentFakes.FakeGateway;
import com.swunion.lab.agent.llm.LlmTypes.ToolResult;
import com.swunion.lab.agent.llm.LlmTypes.ToolSpec;
import com.swunion.lab.course.Course;
import com.swunion.lab.docker.DockerOperationException;
import com.swunion.lab.docker.ExecResult;
import com.swunion.lab.session.SessionService;
import com.swunion.lab.session.StudentSession;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class AgentToolsTest {

	FakeGateway docker = new FakeGateway();
	FakeChecker checker = new FakeChecker();
	SessionService sessions = mock(SessionService.class);
	AgentTools tools;
	RunState state;
	StudentSession session;
	AgentContext check;
	AgentContext help;

	@BeforeEach
	void setUp() {
		tools = new AgentTools(checker, docker, sessions, JsonMapper.builder().build());
		state = new RunState();
		session = new StudentSession(new Course("수업", "3.12", "six==1.16.0"), "학생", "tok", "pw");
		ReflectionTestUtils.setField(session, "id", 11L);
		session.setContainerId("c1");
		check = new AgentContext(session, Trigger.CHECK, MISSING_SIX, null, null, null);
		help = new AgentContext(session, Trigger.HELP, OK, "IndexError", "IndexError: list index out of range", 3L);
	}

	@Test
	void specsAreTheSixAllowlistedTools() {
		assertThat(AgentTools.specs()).extracting(ToolSpec::name).containsExactly("check_environment",
				"restore_packages", "restart_ide", "explain_to_student", "escalate_to_instructor", "finish");
	}

	@Test
	void unknownToolIsRejectedWithoutSideEffects() {
		ToolResult r = tools.execute(check, call("1", "run_shell", Map.of("cmd", "rm -rf /")), state);
		assertThat(r.isError()).isTrue();
		assertThat(r.toolCallId()).isEqualTo("1");
		assertThat(state.rejected).singleElement().asString().startsWith("run_shell:");
		assertThat(docker.execs).isEmpty();
		assertThat(checker.checkCount).isZero();
		verifyNoInteractions(sessions);
	}

	@Test
	void extraArgumentIsRejectedWithoutExec() {
		ToolResult r = tools.execute(check, call("2", "restore_packages", Map.of("version", "1.0")), state);
		assertThat(r.isError()).isTrue();
		assertThat(state.rejected).singleElement().asString().startsWith("restore_packages:");
		assertThat(docker.execs).isEmpty();
	}

	@Test
	void tooLongMeaningIsRejected() {
		ToolResult r = tools.execute(help, call("3", "explain_to_student",
				Map.of("meaning", "가".repeat(801), "where_to_look", "a.py 3번째 줄")), state);
		assertThat(r.isError()).isTrue();
		assertThat(state.explanation).isNull();

		ToolResult ok = tools.execute(help, call("3b", "explain_to_student",
				Map.of("meaning", "가".repeat(800), "where_to_look", "a.py 3번째 줄")), state);
		assertThat(ok.isError()).isFalse();
		assertThat(state.explanation).contains("a.py 3번째 줄");
	}

	@Test
	void badEnumIsRejected() {
		ToolResult r = tools.execute(check, call("4", "finish", Map.of("classification", "ROOT", "summary", "끝")), state);
		assertThat(r.isError()).isTrue();
		assertThat(state.finished).isFalse();
		assertThat(state.classification).isNull();
	}

	@Test
	void wrongTypeAndMissingRequiredAreRejected() {
		assertThat(tools.execute(check, call("5", "escalate_to_instructor", Map.of("reason", 123)), state).isError())
				.isTrue();
		assertThat(tools.execute(check, call("6", "escalate_to_instructor", Map.of()), state).isError()).isTrue();
		assertThat(tools.execute(check, call("7", "escalate_to_instructor", Map.of("reason", "  ")), state).isError())
				.isTrue();
		assertThat(state.escalationReason).isNull();
		assertThat(state.rejected).hasSize(3);
	}

	@Test
	void restoreRunsExactlyTheLockedSyncOnce() {
		docker.execResult = new ExecResult(0, "", "Installed 1 package in 3ms\n + six==1.16.0");
		ToolResult r = tools.execute(check, call("8", "restore_packages", Map.of()), state);

		assertThat(docker.execs).containsExactly(new ExecCall("c1", List.of("uv", "sync", "--frozen", "--inexact"),
				"/opt/lab/spec", Duration.ofSeconds(120)));
		assertThat(r.isError()).isFalse();
		assertThat(r.content()).contains("종료 코드: 0").contains("six==1.16.0");
		assertThat(state.repairAttempted).isTrue();
		assertThat(state.toolLog).singleElement().asString().startsWith("restore_packages");
	}

	@Test
	void restoreFailureReturnsExitCodeAndOutputTail() {
		String noise = "x".repeat(3000);
		docker.execResult = new ExecResult(2, noise, "error: Permission denied (os error 13)");
		ToolResult r = tools.execute(check, call("9", "restore_packages", Map.of()), state);
		assertThat(r.isError()).isTrue();
		assertThat(r.content()).contains("종료 코드: 2").contains("Permission denied").hasSizeLessThan(1700);
	}

	@Test
	void restoreIsCappedAtTwoPerRunIncludingTimeouts() {
		docker.execFailure = new DockerOperationException("exec timed out after 120s");
		ToolResult first = tools.execute(check, call("a", "restore_packages", Map.of()), state);
		assertThat(first.isError()).isTrue();
		assertThat(first.content()).contains("시간");
		tools.execute(check, call("b", "restore_packages", Map.of()), state);
		ToolResult third = tools.execute(check, call("c", "restore_packages", Map.of()), state);
		assertThat(third.isError()).isTrue();
		assertThat(docker.execs).hasSize(2);
	}

	@Test
	void restartIdeUsesSessionService() {
		when(sessions.restartContainer(11L)).thenReturn(true);
		ToolResult r = tools.execute(check, call("r", "restart_ide", Map.of()), state);
		assertThat(r.isError()).isFalse();
		verify(sessions).restartContainer(11L);
		assertThat(state.repairAttempted).isTrue();
	}

	@Test
	void checkEnvironmentReturnsStructuredResultWithoutRecording() {
		checker.current = MISSING_SIX;
		ToolResult r = tools.execute(check, call("k", "check_environment", Map.of()), state);
		assertThat(r.isError()).isFalse();
		assertThat(r.content()).contains("\"MISSING\"").contains("six");
		assertThat(checker.checkCount).isEqualTo(1);
		assertThat(checker.recordCount).isZero();
	}

	@Test
	void explanationIsStrippedOfCodeBlocks() {
		tools.execute(help, call("e", "explain_to_student", Map.of("meaning",
				"리스트 범위를 벗어났습니다.\n```python\nprint(xs[len(xs)-1])\n```", "where_to_look", "`xs`의 길이")), state);
		assertThat(state.explanation).doesNotContain("```").doesNotContain("print(").contains("`xs`의 길이")
				.contains(CodeBlockStripper.PLACEHOLDER);
	}

	@Test
	void explainIsRejectedOnPeriodicCheck() {
		ToolResult r = tools.execute(check, call("x", "explain_to_student",
				Map.of("meaning", "뜻", "where_to_look", "곳")), state);
		assertThat(r.isError()).isTrue();
		assertThat(state.explanation).isNull();
		assertThat(state.rejected).singleElement().asString().startsWith("explain_to_student:");
	}

	@Test
	void finishRecordsClassificationAndBlocksLaterCalls() {
		ToolResult f = tools.execute(check, call("f", "finish", Map.of("classification", "ENV", "summary", "복구함")),
				state);
		assertThat(f.isError()).isFalse();
		assertThat(state.finished).isTrue();
		assertThat(state.classification).isEqualTo(Classification.ENV);
		assertThat(state.summary).isEqualTo("복구함");

		ToolResult after = tools.execute(check, call("g", "restore_packages", Map.of()), state);
		assertThat(after.isError()).isTrue();
		assertThat(docker.execs).isEmpty();
	}
}
