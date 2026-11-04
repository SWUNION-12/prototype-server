package com.swunion.lab.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swunion.lab.config.LabProperties;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.docker.DockerOperationException;
import com.swunion.lab.docker.ExecResult;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest
class CourseProvisionerTest {

	@Autowired
	CourseRepository repo;
	@TempDir
	Path dataDir;

	DockerGateway docker = mock(DockerGateway.class);
	UvLocker locker = mock(UvLocker.class);
	JsonMapper json = JsonMapper.builder().build();
	CourseProvisioner provisioner;

	@BeforeEach
	void setUp() {
		var props = new LabProperties("i", "p", "m", 15, "http://x", "127.0.0.1", dataDir, "swunion/lab-base:1");
		provisioner = new CourseProvisioner(repo, docker, locker, props, json);
		when(docker.imageExists("swunion/lab-base:1")).thenReturn(true);
	}

	private Course newCourse() {
		return repo.save(new Course("수업", "3.12", "six==1.16.0"));
	}

	@Test
	void lockFailureStopsBeforeBuild() {
		when(locker.lock(any())).thenReturn(new LockResult(false, "conflict urllib3"));
		Course c = newCourse();
		provisioner.provision(c.getId());
		Course after = repo.findById(c.getId()).orElseThrow();
		assertThat(after.getStatus()).isEqualTo(CourseStatus.FAILED);
		assertThat(after.getErrorSummary()).contains("urllib3");
		assertThat(after.getEntryCode()).isNull();
		verify(docker, never()).buildImage(any(), anyString(), any());
	}

	@Test
	void successSetsReadyBaselineAndEntryCode() throws Exception {
		when(locker.lock(any())).thenReturn(new LockResult(true, null));
		when(docker.buildImage(any(), anyString(), any())).thenReturn("sha256:x");
		when(docker.runOnce(anyString(), anyList(), any()))
				.thenReturn(new ExecResult(0, "[{\"name\":\"Six\",\"version\":\"1.16.0\"}]", ""));
		Course c = newCourse();
		provisioner.provision(c.getId());
		Course after = repo.findById(c.getId()).orElseThrow();
		assertThat(after.getStatus()).isEqualTo(CourseStatus.READY);
		assertThat(after.getEntryCode()).matches("[A-Z0-9]{6}");
		assertThat(after.getImageName()).isEqualTo("swunion/course-" + c.getId() + ":1");
		Map<String, String> baseline = json.readValue(after.getBaselineJson(),
				new tools.jackson.core.type.TypeReference<Map<String, String>>() {
				});
		assertThat(baseline).containsExactly(Map.entry("six", "1.16.0"));
		assertThat(dataDir.resolve("courses").resolve(String.valueOf(c.getId())).resolve("Dockerfile")).exists();
	}

	@Test
	void missingBaseImageFails() {
		when(docker.imageExists("swunion/lab-base:1")).thenReturn(false);
		Course c = newCourse();
		provisioner.provision(c.getId());
		Course after = repo.findById(c.getId()).orElseThrow();
		assertThat(after.getStatus()).isEqualTo(CourseStatus.FAILED);
		assertThat(after.getErrorSummary()).contains("scripts/build-base-image.sh");
	}

	@Test
	void buildExceptionBecomesFailed() {
		when(locker.lock(any())).thenReturn(new LockResult(true, null));
		when(docker.buildImage(any(), anyString(), any())).thenThrow(new DockerOperationException("빌드 깨짐"));
		Course c = newCourse();
		provisioner.provision(c.getId());
		Course after = repo.findById(c.getId()).orElseThrow();
		assertThat(after.getStatus()).isEqualTo(CourseStatus.FAILED);
		assertThat(after.getErrorSummary()).contains("빌드 깨짐");
	}

	@Test
	void interruptedCoursesFailOnStartup() {
		Course locking = newCourse();
		Course building = newCourse();
		building.setStatus(CourseStatus.BUILDING);
		repo.save(building);
		Course ready = newCourse();
		ready.setStatus(CourseStatus.READY);
		repo.save(ready);
		new CourseRecovery(repo).failInterruptedCourses();
		assertThat(repo.findById(locking.getId()).orElseThrow().getStatus()).isEqualTo(CourseStatus.FAILED);
		assertThat(repo.findById(building.getId()).orElseThrow().getErrorSummary()).isEqualTo("서버 재시작으로 중단");
		assertThat(repo.findById(ready.getId()).orElseThrow().getStatus()).isEqualTo(CourseStatus.READY);
	}
}
