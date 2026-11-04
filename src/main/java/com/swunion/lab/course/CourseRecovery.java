package com.swunion.lab.course;

import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 서버가 발급 도중에 꺼졌다면 그 수업은 영영 LOCKING/BUILDING에 남으므로 시작할 때 FAILED로 정리한다. */
@Component
public class CourseRecovery {

	private final CourseRepository repo;

	public CourseRecovery(CourseRepository repo) {
		this.repo = repo;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void failInterruptedCourses() {
		List<Course> stuck = repo.findByStatusIn(List.of(CourseStatus.LOCKING, CourseStatus.BUILDING));
		for (Course c : stuck) {
			c.setStatus(CourseStatus.FAILED);
			c.setErrorSummary("서버 재시작으로 중단");
			repo.save(c);
		}
	}
}
