package com.swunion.lab.course;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CourseService {

	private final CourseRepository repo;
	private final CourseProvisioner provisioner;
	private final TaskExecutor executor;
	private final JsonMapper json;

	public CourseService(CourseRepository repo, CourseProvisioner provisioner,
			@Qualifier("courseExecutor") TaskExecutor executor, JsonMapper json) {
		this.repo = repo;
		this.provisioner = provisioner;
		this.executor = executor;
		this.json = json;
	}

	/** 검증은 동기로 하고(실패하면 InvalidSpecException), 락과 빌드는 백그라운드로 넘긴다. */
	public Course create(String name, String pythonVersion, String packagesText) {
		String cleanName = name == null ? "" : name.trim();
		if (cleanName.isEmpty() || cleanName.length() > 100) {
			throw new InvalidSpecException("수업 이름은 1자 이상 100자 이하로 입력해 주세요.");
		}
		String python = SpecValidator.parsePythonVersion(pythonVersion);
		List<PackageSpec> packages = SpecValidator.parsePackages(packagesText);
		String normalized = packages.stream().map(PackageSpec::toLine).collect(Collectors.joining("\n"));
		Course course = repo.save(new Course(cleanName, python, normalized));
		long id = course.getId();
		try {
			executor.execute(() -> provisioner.provision(id));
		}
		catch (TaskRejectedException e) {
			// 큐가 가득 차면 저장된 행이 LOCKING에 갇히므로 바로 FAILED로 돌린다.
			course.setStatus(CourseStatus.FAILED);
			course.setErrorSummary("서버가 바빠 발급을 시작하지 못했습니다. 다시 시도해 주세요");
			course = repo.save(course);
		}
		return course;
	}

	public List<Course> list() {
		return repo.findAllByOrderByIdDesc();
	}

	public Course get(long id) {
		return repo.findById(id).orElse(null);
	}

	public Map<String, String> baseline(Course course) {
		if (course.getBaselineJson() == null) {
			return Map.of();
		}
		return json.readValue(course.getBaselineJson(), new TypeReference<Map<String, String>>() {
		});
	}

	public Path specDir(long courseId) {
		return provisioner.specDir(courseId);
	}
}
