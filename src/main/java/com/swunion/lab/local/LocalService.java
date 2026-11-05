package com.swunion.lab.local;

import com.swunion.lab.check.PackageNames;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseService;
import com.swunion.lab.course.PackageSpec;
import com.swunion.lab.course.SpecValidator;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.session.SessionNotFoundException;
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
	private final DownloadCodes codes;

	public LocalService(SessionService sessions, CourseService courses, DockerGateway docker,
			InstallScriptRenderer renderer, LocalInstallRepository repo, JsonMapper json, DownloadCodes codes) {
		this.sessions = sessions;
		this.courses = courses;
		this.docker = docker;
		this.renderer = renderer;
		this.repo = repo;
		this.json = json;
		this.codes = codes;
	}

	/**
	 * "내 PC로 이어가기" 명령. 부를 때마다 새 다운로드 코드를 만들어 설치 명령과 스크립트·zip 주소에 싣는다.
	 * expiresAt이 지나면 화면이 다시 받는다.
	 */
	public Map<String, String> link(StudentSession s, String os) {
		requireOs(os);
		DownloadCodes.Issued issued = codes.issue(s);
		String code = issued.code();
		Map<String, String> m = new LinkedHashMap<>();
		m.put("diagnoseCommand", renderer.diagnoseCommand(os));
		m.put("installCommand", renderer.installCommand(os, code));
		m.put("scriptUrl", renderer.scriptUrl(os, code));
		m.put("zipUrl", renderer.zipUrl(code));
		m.put("expiresAt", issued.expiresAt().toString());
		return m;
	}

	/**
	 * 다운로드 코드가 가리키는 세션. 코드가 살아 있어도 재입장으로 토큰이 바뀌었거나 학생·수업이 지워졌으면 그 코드를
	 * 버리고 만료와 같게 다룬다.
	 *
	 * @throws DownloadCodeExpiredException
	 */
	public StudentSession byCode(String code) {
		String token = codes.tokenFor(code);
		try {
			return sessions.byToken(token);
		}
		catch (SessionNotFoundException e) {
			codes.revoke(code);
			throw new DownloadCodeExpiredException();
		}
	}

	public void saveDiagnostics(StudentSession s, String os, String text) {
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

	public Map<String, Object> verify(StudentSession s, String text) {
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

	/** @param code 스크립트가 수업 파일을 받을 때 다시 쓰는 다운로드 코드(이 스크립트를 받은 그 코드) */
	public String installSh(StudentSession s, String code) {
		return renderer.installSh(code, s.getCourse().getId());
	}

	public String installPs1(StudentSession s, String code) {
		return renderer.installPs1(code, s.getCourse().getId());
	}

	/** @return 허용 이름이 아니거나 파일이 아직 없으면 null */
	public String file(StudentSession s, String name) {
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
	public byte[] codeZip(StudentSession s) {
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

	public List<LocalInstall> forCourse(long courseId) {
		return repo.findBySessionCourseId(courseId);
	}

	private static void requireOs(String os) {
		if (!"macos".equals(os) && !"windows".equals(os)) {
			throw new InvalidPasteException("OS는 macOS 또는 Windows 중에서 골라 주세요.");
		}
	}
}
