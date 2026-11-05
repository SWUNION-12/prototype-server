package com.swunion.lab.course;

import com.swunion.lab.config.LabProperties;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.docker.DockerOperationException;
import com.swunion.lab.docker.ExecResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.swunion.lab.check.PackageNames;

/** LOCKING -> BUILDING -> READY 흐름. 어느 단계에서든 예외가 새면 FAILED로 마감한다. */
@Component
public class CourseProvisioner {

	private static final Logger log = LoggerFactory.getLogger(CourseProvisioner.class);
	private static final String ENTRY_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
	private static final SecureRandom RANDOM = new SecureRandom();

	private final CourseRepository repo;
	private final DockerGateway docker;
	private final UvLocker locker;
	private final LabProperties props;
	private final JsonMapper json;

	public CourseProvisioner(CourseRepository repo, DockerGateway docker, UvLocker locker, LabProperties props,
			JsonMapper json) {
		this.repo = repo;
		this.docker = docker;
		this.locker = locker;
		this.props = props;
		this.json = json;
	}

	/** 수업 작업 폴더들이 모이는 곳. 수업 삭제는 이 바로 아래의 자기 폴더만 지운다. */
	public static Path coursesRoot(Path dataDir) {
		return dataDir.resolve("courses");
	}

	/** 수업 작업 폴더. 발급이 쓰고 수업 삭제가 지우므로 두 쪽이 같은 규칙을 쓰게 여기 한 곳에 둔다. */
	public static Path courseDir(Path dataDir, long id) {
		return coursesRoot(dataDir).resolve(String.valueOf(id));
	}

	Path courseDir(long id) {
		return courseDir(props.dataDir(), id);
	}

	Path specDir(long id) {
		return courseDir(id).resolve("spec");
	}

	public void provision(long courseId) {
		Course course = repo.findById(courseId).orElse(null);
		if (course == null) {
			log.warn("발급할 수업을 찾지 못했습니다: {}", courseId);
			return;
		}
		try {
			run(course);
		}
		catch (Throwable t) {
			// 예외 원문에는 경로나 Docker 내부 정보가 섞일 수 있어 강사에게는 일반 문구만 보이고, 상세는 서버 로그에만 남긴다.
			log.error("수업 {} 발급 실패", courseId, t);
			fail(course, "내부 오류로 수업 발급에 실패했습니다. 서버 로그를 확인해 주세요.");
		}
	}

	private void run(Course course) throws IOException {
		long id = course.getId();
		if (!docker.imageExists(props.baseImage())) {
			fail(course, "기본 이미지 " + props.baseImage() + "가 없습니다. scripts/build-base-image.sh를 먼저 실행해 주세요.");
			return;
		}
		// 입력은 create에서 이미 검증했지만, 파일에 쓰기 직전에 같은 규칙으로 다시 파싱한다.
		String python = SpecValidator.parsePythonVersion(course.getPythonVersion());
		List<PackageSpec> packages = SpecValidator.parsePackages(course.getPackagesText());
		Path specDir = specDir(id);
		SpecFiles.write(specDir, id, python, packages);

		LockResult lock = locker.lock(specDir);
		if (!lock.ok()) {
			fail(course, "패키지 해석(uv lock)에 실패했습니다:\n" + scrub(lock.errorSummary(), id));
			return;
		}

		course.setStatus(CourseStatus.BUILDING);
		repo.save(course);
		String tag = props.courseImagePrefix() + id + ":1";
		renderDockerfile(courseDir(id));
		long start = System.nanoTime();
		try {
			docker.buildImage(courseDir(id), tag, line -> {
			});
		}
		catch (DockerOperationException e) {
			// 빌드 로그 끝부분은 강사에게 유용하므로 경로만 지우고 보여준다.
			log.error("수업 {} 이미지 빌드 실패", id, e);
			fail(course, "이미지 빌드에 실패했습니다:\n" + scrub(e.getMessage(), id));
			return;
		}
		log.info("수업 {} 이미지 빌드 완료: {}초", id, Duration.ofNanos(System.nanoTime() - start).toSeconds());
		course.setImageName(tag);

		ExecResult list = docker.runOnce(tag,
				List.of("uv", "pip", "list", "--python", "/opt/venv/bin/python", "--format", "json"),
				Duration.ofSeconds(60));
		if (list.exitCode() != 0) {
			fail(course, "설치된 패키지 목록을 읽지 못했습니다:\n" + scrub(tail(list.stderr()), id));
			return;
		}
		course.setBaselineJson(json.writeValueAsString(parseBaseline(list.stdout())));
		course.setEntryCode(newEntryCode());
		course.setStatus(CourseStatus.READY);
		course.setErrorSummary(null);
		repo.save(course);
	}

	private Map<String, String> parseBaseline(String stdout) {
		Map<String, String> map = new TreeMap<>();
		JsonNode root = json.readTree(stdout);
		for (JsonNode n : root) {
			map.put(PackageNames.normalize(n.get("name").asString()), n.get("version").asString());
		}
		return map;
	}

	private void renderDockerfile(Path courseDir) throws IOException {
		String tmpl;
		try (InputStream in = getClass().getResourceAsStream("/course/Dockerfile.tmpl")) {
			tmpl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		Files.writeString(courseDir.resolve("Dockerfile"), tmpl.replace("{{BASE_IMAGE}}", props.baseImage()),
				StandardCharsets.UTF_8);
	}

	private String newEntryCode() {
		while (true) {
			StringBuilder sb = new StringBuilder(6);
			for (int i = 0; i < 6; i++) {
				sb.append(ENTRY_ALPHABET.charAt(RANDOM.nextInt(ENTRY_ALPHABET.length())));
			}
			if (!repo.existsByEntryCode(sb.toString())) {
				return sb.toString();
			}
		}
	}

	/** 호스트의 절대 경로를 짧은 표시로 바꾼다. 긴 경로부터 바꿔야 짧은 경로가 먼저 잘라먹지 않는다. */
	String scrub(String text, long id) {
		String s = text == null || text.isBlank() ? "(상세 메시지 없음)" : text;
		java.util.Map<Path, String> paths = new java.util.LinkedHashMap<>();
		paths.put(specDir(id), "<spec>");
		paths.put(courseDir(id), "<course>");
		paths.put(props.dataDir(), "<data>");
		for (var e : paths.entrySet()) {
			Path abs = e.getKey().toAbsolutePath().normalize();
			java.util.Set<String> variants = new java.util.LinkedHashSet<>();
			variants.add(abs.toString());
			try {
				variants.add(abs.toRealPath().toString());
			}
			catch (IOException | RuntimeException ignored) {
				// 아직 없는 경로는 실제 경로를 구할 수 없다.
			}
			for (String v : variants) {
				s = s.replace(v, e.getValue());
			}
		}
		return s;
	}

	private void fail(Course course, String summary) {
		course.setStatus(CourseStatus.FAILED);
		course.setErrorSummary(summary.length() > 3900 ? summary.substring(0, 3900) : summary);
		repo.save(course);
	}

	private static String tail(String s) {
		String t = s == null ? "" : s.strip();
		return t.length() <= 2000 ? t : t.substring(t.length() - 2000);
	}
}
