package com.swunion.lab.session;

import com.swunion.lab.course.CourseStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionRepository extends JpaRepository<StudentSession, Long> {

	Optional<StudentSession> findByToken(String token);

	List<StudentSession> findByCourse_StatusAndContainerIdIsNotNull(CourseStatus status);
}
