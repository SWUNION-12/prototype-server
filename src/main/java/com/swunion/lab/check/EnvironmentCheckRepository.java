package com.swunion.lab.check;

import com.swunion.lab.session.StudentSession;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface EnvironmentCheckRepository extends JpaRepository<EnvironmentCheck, Long> {

	Optional<EnvironmentCheck> findFirstBySessionOrderByIdDesc(StudentSession session);

	List<EnvironmentCheck> findBySessionOrderByIdAsc(StudentSession session);

	// 학생·수업 삭제(LifecycleRows)가 세션 행보다 먼저 부른다(외래 키).

	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from EnvironmentCheck c where c.session.id = :sessionId")
	int deleteBySessionId(@Param("sessionId") long sessionId);

	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from EnvironmentCheck c where c.session.id in"
			+ " (select s.id from StudentSession s where s.course.id = :courseId)")
	int deleteByCourseId(@Param("courseId") long courseId);
}
