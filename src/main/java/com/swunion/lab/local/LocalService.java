package com.swunion.lab.local;

import com.swunion.lab.check.PackageNames;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseService;
import com.swunion.lab.course.PackageSpec;
import com.swunion.lab.course.SpecValidator;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.session.SessionService;
import com.swunion.lab.session.StudentSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class LocalService {

	private static final Logger log = LoggerFactory.getLogger(LocalService.class);

	/** 이 이름만 내려준다. 요청 값으로 경로를 만들지 않고 이 집합에서 고른 상수로만 파일을 연다. */
	static final Set<String> SPEC_FILES = Set.of("pyproject.toml", "uv.lock", ".python-version");

	private final SessionService sessions;
	private final CourseService courses;
	private final DockerGateway docker;
	private final InstallScriptRenderer renderer;
	private final LocalInstallRepository repo;
	private final JsonMapper json;

	public LocalService(SessionService sessions, CourseService courses, DockerGateway docker,
			InstallScriptRenderer renderer, LocalInstallRepository repo, JsonMapper json) {
		this.sessions = sessions;
		this.courses = courses;
		this.docker = docker;
		this.renderer = renderer;
		this.repo = repo;
		this.json = json;
	}

	public Map<String, String> installInfo(String token, String os) {
		sessions.byToken(token);
		requireOs(os);
		Map<String, String> m = new LinkedHashMap<>();
		m.put("diagnoseCommand", renderer.diagnoseCommand(os));
		m.put("installCommand", renderer.installCommand(os, token));
		m.put("scriptUrl", renderer.scriptUrl(os, token));
		m.put("zipUrl", renderer.zipUrl(token));
		return m;
	}

	public void saveDiagnostics(String token, String os, String text) {
		StudentSession s = sessions.byToken(token);
		Diagnostics d = DiagnosticsParser.parse(text, os);
		LocalInstall li = repo.findBySessionId(s.getId()).orElseGet(() -> new LocalInstall(s));
		li.setOs(d.os());
		li.setArch(d.arch());
		li.setPythonVersion(d.python());
		li.setStatus(LocalStatus.DIAGNOSED);
		li.setFailedJson(null);
		li.setUpdatedAt(Instant.now());
		repo.save(li);
	}

	public Map<String, Object> verify(String token, String text) {
		StudentSession s = sessions.byToken(token);
		VerifyResult r = VerifyOutputParser.parse(text);
		LocalInstall li = repo.findBySessionId(s.getId()).orElseGet(() -> new LocalInstall(s));
		li.setStatus(r.ok() ? LocalStatus.OK : LocalStatus.FAIL);
		if (r.python() != null) {
			li.setPythonVersion(r.python());
		}
		li.setFailedJson(r.ok() ? null : json.writeValueAsString(r.failed()));
		li.setUpdatedAt(Instant.now());
		repo.save(li);
		if (!r.ok()) {
			sessions.flagNeedsHelp(s.getId(), "로컬 설치 검증 실패: " + String.join(",", r.failed()));
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("ok", r.ok());
		m.put("python", r.python());
		m.put("passed", r.passed());
		m.put("total", r.total());
		m.put("failed", r.failed());
		m.put("message", r.ok() ? "내 컴퓨터에 수업과 같은 환경이 설치되었습니다."
				: "로컬 설치 검증에 실패했습니다. 강사에게 알렸습니다. 지금은 클라우드 IDE에서 실습을 이어가 주세요.");
		return m;
	}

	public String installSh(String token) {
		return renderer.installSh(token, sessions.byToken(token).getCourse().getId());
	}

	public String installPs1(String token) {
		return renderer.installPs1(token, sessions.byToken(token).getCourse().getId());
	}

	/** @return 허용 이름이 아니거나 파일이 아직 없으면 null */
	public String file(String token, String name) {
		StudentSession s = sessions.byToken(token);
		Course course = s.getCourse();
		if (name.equals("verify.py")) {
			return renderer.verifyPy(expectedDirect(course));
		}
		if (!SPEC_FILES.contains(name)) {
			return null;
		}
		Path p = courses.specDir(course.getId()).resolve(name);
		if (!Files.isRegularFile(p)) {
			return null;
		}
		try {
			return Files.readString(p, StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** 직접 지정한 패키지의 기준 버전. 기준에 없으면 null로 두어 import만 확인한다. */
	Map<String, String> expectedDirect(Course course) {
		Map<String, String> baseline = courses.baseline(course);
		Map<String, String> expected = new LinkedHashMap<>();
		for (PackageSpec p : SpecValidator.parsePackages(course.getPackagesText())) {
			String name = PackageNames.normalize(p.name());
			expected.put(name, baseline.get(name));
		}
		return expected;
	}

	/** 컨테이너가 멈춰 있어도 docker cp는 된다. 실패하면 CodeUnavailableException. */
	public byte[] codeZip(String token) {
		StudentSession s = sessions.byToken(token);
		if (s.getContainerId() == null) {
			throw new CodeUnavailableException("클라우드 환경이 아직 만들어지지 않았습니다.");
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (InputStream tar = docker.copyArchive(s.getContainerId(), "/home/coder/project")) {
			CodeZipper.tarToZip(tar, out, "course-" + s.getCourse().getId());
		}
		catch (RuntimeException | IOException e) {
			log.warn("세션 {} 코드 zip 생성 실패", s.getId(), e);
			throw new CodeUnavailableException("클라우드 코드를 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.", e);
		}
		return out.toByteArray();
	}

	public Course courseOf(String token) {
		return sessions.byToken(token).getCourse();
	}

	public List<LocalInstall> forCourse(long courseId) {
		return repo.findBySessionCourseId(courseId);
	}

	private static void requireOs(String os) {
		if (!"macos".equals(os) && !"windows".equals(os)) {
			throw new InvalidPasteException("OS는 macOS 또는 Windows 중에서 골라 주세요.");
		}
	}
}
