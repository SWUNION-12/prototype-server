package com.swunion.lab.local;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface LocalInstallRepository extends JpaRepository<LocalInstall, Long> {

	Optional<LocalInstall> findBySessionId(Long sessionId);

	/** 강사 대시보드가 수업의 로컬 설치 상태를 한 번에 읽는다. */
	List<LocalInstall> findBySessionCourseId(Long courseId);

	// 학생·수업 삭제(LifecycleRows)가 세션 행보다 먼저 부른다(외래 키).

	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from LocalInstall l where l.session.id = :sessionId")
	int deleteBySessionId(@Param("sessionId") long sessionId);

	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from LocalInstall l where l.session.id in"
			+ " (select s.id from StudentSession s where s.course.id = :courseId)")
	int deleteByCourseId(@Param("courseId") long courseId);
}
