package com.swunion.lab.agent;

import com.swunion.lab.agent.llm.LlmTypes.ToolCall;
import com.swunion.lab.agent.llm.LlmTypes.ToolResult;
import com.swunion.lab.agent.llm.LlmTypes.ToolSpec;
import com.swunion.lab.check.CheckResult;
import com.swunion.lab.check.EnvironmentChecker;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.docker.ExecResult;
import com.swunion.lab.session.SessionService;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 에이전트가 쓸 수 있는 도구의 허용 목록과 실행. 모델이 보낸 이름·인자는 신뢰하지 않는다.
 * 허용 목록 밖의 이름, 정해지지 않은 인자 키, 타입·길이·값 위반은 실행하지 않고 거부로 기록한다.
 * 어떤 도구도 인자로 명령어나 경로를 받지 않는다.
 */
@Component
public class AgentTools {

	private static final Logger log = LoggerFactory.getLogger(AgentTools.class);

	// PATH의 첫 항목 /opt/venv/bin은 학생(coder)이 쓸 수 있어 "uv"로 부르면 학생이 둔 uv가 실행될 수 있다. root 소유 절대 경로를 쓴다.
	static final List<String> RESTORE_CMD = List.of("/usr/local/bin/uv", "sync", "--frozen", "--inexact");
	static final String SPEC_DIR = "/opt/lab/spec";
	static final Duration RESTORE_TIMEOUT = Duration.ofSeconds(120);
	// 시간 초과된 exec는 컨테이너 안에서 계속 돌 수 있어, 겹쳐 돌리지 않도록 실행당 횟수를 묶는다.
	static final int MAX_RESTORES = 2;
	// 재시작마다 IDE 응답을 최대 60초 기다린다.
	static final int MAX_RESTARTS = 2;
	static final int OUTPUT_TAIL = 1500;
	// AgentAction 컬럼 길이. 코드 블록 자리 안내 문구가 원문보다 길 수 있어 지운 뒤에 다시 자른다.
	static final int MAX_EXPLANATION = 4000;
	static final int MAX_SUMMARY = 2000;
	private static final int LOG_PART = 300;

	/** 인자 규칙. enumValues가 있으면 그 값만 허용. 모든 인자는 필수 문자열이다. */
	private record Arg(String name, int maxLength, List<String> enumValues, String description) {
	}

	private static final List<String> FINISH_CLASSES = List.of("ENV", "CODE", "OUT_OF_SCOPE");

	private static final Map<String, List<Arg>> ARGS = new LinkedHashMap<>();
	private static final Map<String, String> DESCRIPTIONS = new LinkedHashMap<>();

	static {
		define("check_environment", "학생 컨테이너를 다시 점검합니다. 컨테이너 실행 여부, IDE 접속, 파이썬 버전, 패키지를 "
				+ "스펙과 비교한 결과를 돌려줍니다. 인자는 없습니다.");
		define("restore_packages", "수업 lock 파일 기준으로 패키지를 복구합니다(서버가 정한 uv sync 명령). 학생이 추가로 "
				+ "설치한 패키지는 지우지 않습니다. 스펙 패키지가 없거나 버전이 다를 때, 파이썬 버전이 다를 때 씁니다. "
				+ "종료 코드와 출력 끝부분을 돌려줍니다. 인자는 없습니다.");
		define("restart_ide", "학생 컨테이너를 재시작하고 IDE(code-server)가 응답하는지 확인합니다. 컨테이너나 IDE가 "
				+ "중단됐을 때(IDE_DOWN)만 씁니다. 재시작하면 학생의 터미널이 끊깁니다. 인자는 없습니다.");
		define("explain_to_student", "도움을 요청한 학생에게 설명을 보냅니다. 오류의 의미와 확인할 곳만 쓰고, 정답 코드나 "
				+ "고친 코드는 쓰지 않습니다. 코드 블록은 서버가 지웁니다. 도움 요청(HELP)일 때만 쓸 수 있습니다.",
				new Arg("meaning", 800, null, "오류가 무슨 뜻인지. 한국어."),
				new Arg("where_to_look", 400, null, "학생이 확인할 곳(파일, 줄, 변수 이름 등). 한국어."));
		define("escalate_to_instructor", "강사 도움이 필요하다고 표시합니다. 스펙 밖 문제, 복구 실패, 원인 불명일 때 씁니다.",
				new Arg("reason", 300, null, "강사에게 보일 짧은 이유. 한국어."));
		define("finish", "실행을 끝냅니다. 마지막에 정확히 한 번 호출합니다.",
				new Arg("classification", 20, FINISH_CLASSES, "판단 유형"),
				new Arg("summary", 500, null, "무엇을 판단하고 무엇을 했는지 짧은 요약. 한국어."));
	}

	private static void define(String name, String description, Arg... args) {
		ARGS.put(name, List.of(args));
		DESCRIPTIONS.put(name, description);
	}

	private static final List<ToolSpec> SPECS = ARGS.keySet().stream().map(AgentTools::spec).toList();

	private final EnvironmentChecker checker;
	private final DockerGateway docker;
	private final SessionService sessions;
	private final JsonMapper json;

	public AgentTools(EnvironmentChecker checker, DockerGateway docker, SessionService sessions, JsonMapper json) {
		this.checker = checker;
		this.docker = docker;
		this.sessions = sessions;
		this.json = json;
	}

	public static List<ToolSpec> specs() {
		return SPECS;
	}

	private static ToolSpec spec(String name) {
		Map<String, Object> props = new LinkedHashMap<>();
		for (Arg a : ARGS.get(name)) {
			Map<String, Object> p = new LinkedHashMap<>();
			p.put("type", "string");
			if (a.enumValues() != null) {
				p.put("enum", a.enumValues());
			}
			else {
				p.put("maxLength", a.maxLength());
			}
			p.put("description", a.description());
			props.put(a.name(), p);
		}
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("properties", props);
		if (!props.isEmpty()) {
			schema.put("required", List.copyOf(props.keySet()));
		}
		schema.put("additionalProperties", false);
		return new ToolSpec(name, DESCRIPTIONS.get(name), schema);
	}

	/** 절대 던지지 않는다. 실패와 거부는 isError 결과로 돌려주어 tool_use/tool_result 짝을 지킨다. */
	public ToolResult execute(AgentContext ctx, ToolCall call, RunState state) {
		String name = shortName(call.name());
		if (state.finished) {
			return reject(call, state, name, "finish 이후의 호출은 실행하지 않습니다.");
		}
		List<Arg> rules = call.name() == null ? null : ARGS.get(call.name());
		if (rules == null) {
			return reject(call, state, name, "허용 목록에 없는 도구입니다.");
		}
		Map<String, Object> input = call.input() == null ? Map.of() : call.input();
		String invalid = validate(rules, input);
		if (invalid != null) {
			return reject(call, state, name, invalid);
		}
		Map<String, String> args = input.entrySet().stream()
				.collect(Collectors.toMap(Map.Entry::getKey, e -> (String) e.getValue()));
		try {
			return switch (call.name()) {
				case "check_environment" -> checkEnvironment(ctx, call, state);
				case "restore_packages" -> restorePackages(ctx, call, state);
				case "restart_ide" -> restartIde(ctx, call, state);
				case "explain_to_student" -> explain(ctx, call, state, args);
				case "escalate_to_instructor" -> escalate(call, state, args);
				case "finish" -> finish(call, state, args);
				default -> reject(call, state, name, "허용 목록에 없는 도구입니다.");
			};
		}
		catch (RuntimeException e) {
			log.warn("도구 실행 실패 session={} tool={}: {}", ctx.session().getId(), call.name(), e.toString());
			return logged(call, state, args, "실패: " + e.getClass().getSimpleName(), true);
		}
	}

	private static String validate(List<Arg> rules, Map<String, Object> input) {
		Set<String> allowed = rules.stream().map(Arg::name).collect(Collectors.toSet());
		for (String key : input.keySet()) {
			if (!allowed.contains(key)) {
				return "허용되지 않은 인자입니다: " + shortName(key);
			}
		}
		for (Arg a : rules) {
			Object v = input.get(a.name());
			if (!(v instanceof String s)) {
				return v == null ? "필수 인자가 없습니다: " + a.name() : "문자열이 아닌 인자입니다: " + a.name();
			}
			if (s.isBlank()) {
				return "빈 인자입니다: " + a.name();
			}
			if (s.length() > a.maxLength()) {
				return a.name() + "가 " + a.maxLength() + "자를 넘습니다.";
			}
			if (a.enumValues() != null && !a.enumValues().contains(s)) {
				return a.name() + "는 " + String.join(", ", a.enumValues()) + " 중 하나여야 합니다.";
			}
		}
		return null;
	}

	private ToolResult checkEnvironment(AgentContext ctx, ToolCall call, RunState state) {
		// 기록하지 않는 점검이다. 기록(checkAndRecord)은 envStatus를 바꾸므로 실행 끝 재점검에서만 한다.
		CheckResult r = checker.check(ctx.session());
		return logged(call, state, Map.of(), json.writeValueAsString(viewMap(r)), false);
	}

	/** 모델에게 보여 주는 점검 결과 모양. 컨텍스트 메시지와 check_environment 결과가 같은 모양을 쓴다. */
	static Map<String, Object> viewMap(CheckResult r) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("containerRunning", r.containerRunning());
		m.put("ideConnected", r.ideConnected());
		m.put("issues", r.issues());
		m.put("extras", r.extras());
		return m;
	}

	private ToolResult restorePackages(AgentContext ctx, ToolCall call, RunState state) {
		if (state.restoreAttempts >= MAX_RESTORES) {
			return reject(call, state, call.name(), "이번 실행에서 복구를 이미 " + MAX_RESTORES + "번 시도했습니다.");
		}
		String containerId = ctx.session().getContainerId();
		if (containerId == null) {
			return logged(call, state, Map.of(), "컨테이너가 없어 복구할 수 없습니다.", true);
		}
		state.restoreAttempts++;
		state.repairAttempted = true;
		ExecResult r;
		try {
			r = docker.exec(containerId, RESTORE_CMD, SPEC_DIR, RESTORE_TIMEOUT);
		}
		catch (RuntimeException e) {
			log.warn("복구 exec 실패 session={}: {}", ctx.session().getId(), e.getMessage());
			return logged(call, state, Map.of(), "복구 명령이 시간 초과(" + RESTORE_TIMEOUT.toSeconds()
					+ "초)됐거나 실행되지 않았습니다. 컨테이너 안에서 아직 실행 중일 수 있습니다.", true);
		}
		String output = Stream.of(r.stdout(), r.stderr()).filter(s -> s != null && !s.isBlank())
				.collect(Collectors.joining("\n"));
		String tail = output.length() > OUTPUT_TAIL ? "…" + output.substring(output.length() - OUTPUT_TAIL) : output;
		return logged(call, state, Map.of(), "종료 코드: " + r.exitCode() + "\n출력 끝부분:\n" + tail,
				r.exitCode() != 0);
	}

	private ToolResult restartIde(AgentContext ctx, ToolCall call, RunState state) {
		if (state.restartAttempts >= MAX_RESTARTS) {
			return reject(call, state, call.name(), "이번 실행에서 재시작을 이미 " + MAX_RESTARTS + "번 시도했습니다.");
		}
		state.restartAttempts++;
		state.repairAttempted = true;
		boolean healthy = sessions.restartContainer(ctx.session().getId());
		return healthy
				? logged(call, state, Map.of(), "컨테이너를 재시작했고 IDE가 응답합니다.", false)
				: logged(call, state, Map.of(), "컨테이너를 재시작하지 못했거나 IDE가 60초 안에 응답하지 않습니다.", true);
	}

	private ToolResult explain(AgentContext ctx, ToolCall call, RunState state, Map<String, String> args) {
		if (ctx.trigger() != Trigger.HELP) {
			// 주기 점검에는 설명을 받을 도움 요청이 없다. 받아 주면 복구한 실행이 EXPLAINED로 기록된다.
			return reject(call, state, call.name(), "주기 점검(CHECK)에서는 학생에게 설명을 보내지 않습니다.");
		}
		String meaning = CodeBlockStripper.strip(args.get("meaning"));
		String where = CodeBlockStripper.strip(args.get("where_to_look"));
		state.explanation = AgentContext.truncate(meaning + "\n\n확인할 곳: " + where, MAX_EXPLANATION);
		boolean stripped = !meaning.equals(args.get("meaning")) || !where.equals(args.get("where_to_look"));
		return logged(call, state, args, stripped ? "설명을 저장했습니다. 코드 블록은 서버가 지웠습니다." : "설명을 저장했습니다.",
				false);
	}

	private ToolResult escalate(ToolCall call, RunState state, Map<String, String> args) {
		state.escalationReason = args.get("reason").strip();
		return logged(call, state, args, "강사 도움 필요로 기록합니다.", false);
	}

	private ToolResult finish(ToolCall call, RunState state, Map<String, String> args) {
		state.classification = Classification.valueOf(args.get("classification"));
		state.summary = AgentContext.truncate(CodeBlockStripper.strip(args.get("summary").strip()), MAX_SUMMARY);
		state.finished = true;
		return logged(call, state, args, "실행을 끝냅니다.", false);
	}

	private ToolResult reject(ToolCall call, RunState state, String name, String reason) {
		state.rejected.add(name + ": " + reason);
		return new ToolResult(call.id(), "거부됨: " + reason, true);
	}

	private ToolResult logged(ToolCall call, RunState state, Map<String, String> args, String result, boolean error) {
		String argText = args.isEmpty() ? "{}" : json.writeValueAsString(args);
		state.toolLog.add(call.name() + " " + cut(argText) + " => " + (error ? "[오류] " : "") + cut(result));
		return new ToolResult(call.id(), result, error);
	}

	private static String cut(String s) {
		return s.length() > LOG_PART ? s.substring(0, LOG_PART) + "…" : s;
	}

	static String shortName(String name) {
		if (name == null) {
			return "(이름 없음)";
		}
		return name.length() > 64 ? name.substring(0, 64) + "…" : name;
	}
}
