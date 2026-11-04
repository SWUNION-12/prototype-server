package com.swunion.lab.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.core.task.TaskRejectedException;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest
class CourseServiceTest {

	@Autowired
	CourseRepository repo;

	@Test
	void rejectedExecutorMarksCourseFailed() {
		var service = new CourseService(repo, mock(CourseProvisioner.class), r -> {
			throw new TaskRejectedException("full");
		}, JsonMapper.builder().build(), null, null);
		Course c = service.create("수업", "3.12", "six");
		assertThat(c.getStatus()).isEqualTo(CourseStatus.FAILED);
		assertThat(c.getErrorSummary()).contains("서버가 바빠");
		assertThat(repo.findById(c.getId()).orElseThrow().getStatus()).isEqualTo(CourseStatus.FAILED);
	}
}
