package com.swunion.lab.dashboard;

import com.swunion.lab.agent.AgentAction;
import com.swunion.lab.agent.AgentActionRepository;
import com.swunion.lab.check.Issue;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.dashboard.DashboardView.ActionView;
import com.swunion.lab.dashboard.DashboardView.CourseView;
import com.swunion.lab.dashboard.DashboardView.ErrorCount;
import com.swunion.lab.dashboard.DashboardView.LocalView;
import com.swunion.lab.dashboard.DashboardView.StudentRow;
import com.swunion.lab.help.HelpRequest;
import com.swunion.lab.help.HelpRequestRepository;
import com.swunion.lab.local.LocalInstall;
import com.swunion.lab.local.LocalInstallRepository;
import com.swunion.lab.session.SessionRepository;
import com.swunion.lab.session.StudentSession;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class DashboardService {

	private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

	static final String REPEATED_ERRORS_NOTE = "도움 버튼을 누른 학생 기준이며 전체 오류 수가 아닙니다.";
	static final int RECENT_ACTIONS = 5;
	static final String UNREADABLE_ISSUES = "점검 결과를 읽을 수 없음";
	// toolLogJson 항목은 "도구이름 {인자} => 결과" 한 줄이다. 도구 이름은 서버 허용 목록이라 이 모양이다.
	// 항목이 길어 잘렸을 때 맨 끝에 붙는 "… N건 생략"은 이 모양이 아니라 걸러진다.
	private static final Pattern TOOL_NAME = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)(?:\\s|$)");
	private static final LocalView NO_LOCAL = new LocalView("NONE", null, null, null, List.of());

	private final CourseRepository courses;
	private final SessionRepository sessions;
	private final AgentActionRepository actions;
	private final HelpRequestRepository helps;
	private final LocalInstallRepository locals;
	private final JsonMapper json;

	public DashboardService(CourseRepository courses, SessionRepository sessions, AgentActionRepository actions,
			HelpRequestRepository helps, LocalInstallRepository locals, JsonMapper json) {
		this.courses = courses;
		this.sessions = sessions;
		this.actions = actions;
		this.helps = helps;
		this.locals = locals;
		this.json = json;
	}

	/**
	 * 3초마다 불리므로 수업 단위로 한 번씩만 읽어 메모리에서 나눈다(학생마다 조회하지 않는다).
	 * 한 트랜잭션에서 읽으면 한 시점의 값으로 한 화면이 만들어지고, 읽어 둔 세션을 다른 조회가 다시 가져오지 않는다.
	 */
	@Transactional(readOnly = true)
	public Optional<DashboardView> dashboard(long courseId) {
		Course course = courses.findById(courseId).orElse(null);
		if (course == null) {
			return Optional.empty();
		}
		List<StudentSession> students = sessions.findByCourse_Id(courseId);
		Map<Long, LocalInstall> localBySession = new HashMap<>();
		for (LocalInstall li : locals.findBySessionCourseId(courseId)) {
			localBySession.put(li.getSession().getId(), li);
		}
		Map<Long, List<ActionView>> actionsBySession = recentActions(courseId);

		List<StudentRow> rows = students.stream()
				.map(s -> row(s, localBySession.get(s.getId()), actionsBySession.getOrDefault(s.getId(), List.of())))
				.sorted(Comparator.comparing(StudentRow::needsHelp).reversed()
						.thenComparing(StudentRow::name, Comparator.nullsLast(Comparator.naturalOrder()))
						.thenComparingLong(StudentRow::sessionId))
				.toList();
		return Optional.of(new DashboardView(courseView(course), rows,
				repeatedErrors(helps.findBySessionCourseIdAndErrorTypeIsNotNull(courseId)), REPEATED_ERRORS_NOTE));
	}

	/**
	 * 강사가 도움 필요를 처리 완료로 바꾼다. needsHelp와 이유만 지운다. lastEscalatedSignature는 남겨서,
	 * 같은 불일치가 그대로면 점검이 에이전트를 곧바로 다시 부르지 않게 한다.
	 *
	 * @return 세션이 없거나 그 수업의 세션이 아니면 false
	 */
	public boolean resolve(long courseId, long sessionId) {
		StudentSession s = sessions.findById(sessionId).orElse(null);
		if (s == null || s.getCourse() == null || !Objects.equals(s.getCourse().getId(), courseId)) {
			return false;
		}
		return sessions.updateNeedsHelp(sessionId, false, null) > 0;
	}

	private static CourseView courseView(Course c) {
		List<String> packages = c.getPackagesText() == null ? List.of()
				: Arrays.stream(c.getPackagesText().split("\n")).map(String::strip).filter(p -> !p.isEmpty()).toList();
		return new CourseView(c.getId(), c.getName(), c.getPythonVersion(), packages, c.getStatus().name(),
				c.getEntryCode(), c.getErrorSummary());
	}

	private StudentRow row(StudentSession s, LocalInstall local, List<ActionView> recent) {
		return new StudentRow(s.getId(), s.getDisplayName(), s.isContainerRunning(), s.isIdeConnected(),
				s.getLastSeenAt(), s.getEnvStatus() == null ? null : s.getEnvStatus().name(), issues(s),
				stringList(s.getExtrasJson()), localView(local), s.isNeedsHelp(), s.getNeedsHelpReason(), recent,
				s.getStartupMillis());
	}

	// ---- 환경 불일치 ----

	private List<String> issues(StudentSession s) {
		String raw = s.getIssuesJson();
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		try {
			List<Issue> parsed = json.readValue(raw, new TypeReference<List<Issue>>() {
			});
			return parsed.stream().map(DashboardService::readable).toList();
		}
		catch (RuntimeException e) {
			log.warn("세션 {}의 점검 결과를 읽지 못했습니다: {}", s.getId(), e.getMessage());
			return List.of(UNREADABLE_ISSUES);
		}
	}

	static String readable(Issue i) {
		String name = orUnknown(i.name());
		if (i.type() == null) {
			return name;
		}
		return switch (i.type()) {
			case MISSING -> name + " 없음 (기준 " + orUnknown(i.expected()) + ")";
			case VERSION_MISMATCH -> name + " 버전 다름 (" + orUnknown(i.actual()) + ", 기준 " + orUnknown(i.expected())
					+ ")";
			// 가상환경이 깨져 파이썬을 실행하지 못하면 점검이 actual에 "error"를 넣는다.
			case PYTHON_MISMATCH -> "error".equals(i.actual()) ? "파이썬 실행 실패"
					: "파이썬 버전 다름 (" + orUnknown(i.actual()) + ", 기준 " + orUnknown(i.expected()) + ")";
			case IDE_DOWN -> "IDE 중단";
		};
	}

	private static String orUnknown(String s) {
		return s == null ? "알 수 없음" : s;
	}

	// ---- 로컬 설치 ----

	private LocalView localView(LocalInstall li) {
		if (li == null || li.getStatus() == null) {
			return NO_LOCAL;
		}
		return new LocalView(li.getStatus().name(), li.getOs(), li.getArch(), li.getPythonVersion(),
				stringList(li.getFailedJson()));
	}

	// ---- 에이전트 조치 ----

	/**
	 * 세션마다 최근 5건. 먼저 id만 읽어 고른 뒤 고른 행만 읽는다: 기록 전체를 읽으면
	 * 도구 로그(최대 100,000자)가 쌓인 만큼 3초마다 메모리로 올라온다.
	 * 한 세션에서 실행은 동시에 하나뿐이고 저장은 실행이 끝난 뒤라, id 순서가 곧 끝난 순서다.
	 */
	private Map<Long, List<ActionView>> recentActions(long courseId) {
		Map<Long, Integer> seen = new HashMap<>();
		List<AgentActionRepository.Ref> keep = new ArrayList<>();
		for (AgentActionRepository.Ref ref : actions.findRefsByCourseNewestFirst(courseId)) {
			if (seen.merge(ref.getSessionId(), 1, Integer::sum) <= RECENT_ACTIONS) {
				keep.add(ref);
			}
		}
		if (keep.isEmpty()) {
			return Map.of();
		}
		Map<Long, AgentAction> byId = actions.findAllById(keep.stream().map(AgentActionRepository.Ref::getId).toList())
				.stream().collect(Collectors.toMap(AgentAction::getId, a -> a));
		Map<Long, List<ActionView>> out = new HashMap<>();
		for (AgentActionRepository.Ref ref : keep) {
			AgentAction a = byId.get(ref.getId());
			if (a != null) {
				out.computeIfAbsent(ref.getSessionId(), k -> new ArrayList<>()).add(actionView(a));
			}
		}
		return out;
	}

	private ActionView actionView(AgentAction a) {
		Instant at = a.getFinishedAt() != null ? a.getFinishedAt() : a.getStartedAt();
		return new ActionView(at, name(a.getTrigger()), name(a.getClassification()), tools(a.getToolLogJson()),
				name(a.getOutcome()), a.getSummary());
	}

	private static String name(Enum<?> e) {
		return e == null ? null : e.name();
	}

	/** AgentRunner가 저장한 모양은 문자열 배열이다. 읽지 못하면 도구 없이 보여 준다(대시보드를 깨지 않는다). */
	private List<String> tools(String toolLogJson) {
		if (toolLogJson == null || toolLogJson.isBlank()) {
			return List.of();
		}
		try {
			List<Object> entries = json.readValue(toolLogJson, new TypeReference<List<Object>>() {
			});
			List<String> names = new ArrayList<>();
			for (Object entry : entries) {
				if (entry instanceof String line) {
					Matcher m = TOOL_NAME.matcher(line);
					if (m.find()) {
						names.add(m.group(1));
					}
				}
			}
			return names;
		}
		catch (RuntimeException e) {
			return List.of();
		}
	}

	// ---- 반복 오류 ----

	private static List<ErrorCount> repeatedErrors(List<HelpRequest> requests) {
		Map<String, List<HelpRequest>> byType = new LinkedHashMap<>();
		for (HelpRequest h : requests) {
			byType.computeIfAbsent(h.getErrorType(), k -> new ArrayList<>()).add(h);
		}
		return byType.entrySet().stream()
				.map(e -> new ErrorCount(e.getKey(), e.getValue().size(),
						e.getValue().stream().map(h -> h.getSession().getDisplayName()).filter(Objects::nonNull)
								.distinct().sorted().toList()))
				.sorted(Comparator.comparingLong(ErrorCount::count).reversed().thenComparing(ErrorCount::errorType))
				.toList();
	}

	// ---- 공통 ----

	private List<String> stringList(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		try {
			return json.readValue(raw, new TypeReference<List<String>>() {
			}).stream().filter(Objects::nonNull).toList();
		}
		catch (RuntimeException e) {
			return List.of();
		}
	}
}
