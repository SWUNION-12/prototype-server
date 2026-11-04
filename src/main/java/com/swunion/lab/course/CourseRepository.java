package com.swunion.lab.course;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseRepository extends JpaRepository<Course, Long> {

	boolean existsByEntryCode(String entryCode);

	List<Course> findByStatusIn(Collection<CourseStatus> statuses);

	List<Course> findAllByOrderByIdDesc();
}
