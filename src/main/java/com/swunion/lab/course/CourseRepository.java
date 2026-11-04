package com.swunion.lab.course;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseRepository extends JpaRepository<Course, Long> {

	Optional<Course> findByEntryCode(String entryCode);

	boolean existsByEntryCode(String entryCode);

	List<Course> findByStatusIn(Collection<CourseStatus> statuses);

	List<Course> findAllByOrderByIdDesc();
}
