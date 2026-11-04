package com.swunion.lab.session;

import com.swunion.lab.course.CourseStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SessionRepository extends JpaRepository<StudentSession, Long> {

	Optional<StudentSession> findByToken(String token);

	List<StudentSession> findByCourse_StatusAndContainerIdIsNotNull(CourseStatus status);

	/**
	 * 점검이 소유한 컬럼만 쓴다. 엔티티 전체를 save하면 동시에 바뀐 needsHelp 같은 값을 덮어쓴다.
	 * @return 갱신된 행 수(세션이 지워졌으면 0)
	 */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update StudentSession s set s.containerRunning = :running, s.ideConnected = :ide, s.envStatus = :status,"
			+ " s.issuesJson = :issues, s.extrasJson = :extras where s.id = :id")
	int updateCheckState(@Param("id") long id, @Param("running") boolean running, @Param("ide") boolean ide,
			@Param("status") EnvStatus status, @Param("issues") String issues, @Param("extras") String extras);

	/** updateCheckState와 같되 접속이 확인된 점검이라 lastSeenAt도 함께 쓴다. */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update StudentSession s set s.containerRunning = :running, s.ideConnected = :ide, s.envStatus = :status,"
			+ " s.issuesJson = :issues, s.extrasJson = :extras, s.lastSeenAt = :seen where s.id = :id")
	int updateCheckStateSeen(@Param("id") long id, @Param("running") boolean running, @Param("ide") boolean ide,
			@Param("status") EnvStatus status, @Param("issues") String issues, @Param("extras") String extras,
			@Param("seen") Instant seen);
}
