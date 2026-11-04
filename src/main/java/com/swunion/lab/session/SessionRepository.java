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

	/** 에이전트 실행 시작 표시. 실행 끝의 재점검(updateCheckState*)이 다시 OK/MISMATCH로 바꾼다. */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update StudentSession s set s.envStatus = :status where s.id = :id")
	int updateEnvStatus(@Param("id") long id, @Param("status") EnvStatus status);

	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update StudentSession s set s.needsHelp = :needsHelp, s.needsHelpReason = :reason where s.id = :id")
	int updateNeedsHelp(@Param("id") long id, @Param("needsHelp") boolean needsHelp, @Param("reason") String reason);

	/** 에이전트가 강사에게 넘길 때. 같은 불일치로 다시 호출하지 않도록 서명을 같이 남긴다. */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update StudentSession s set s.needsHelp = true, s.needsHelpReason = :reason,"
			+ " s.lastEscalatedSignature = :signature where s.id = :id")
	int updateEscalation(@Param("id") long id, @Param("reason") String reason, @Param("signature") String signature);

	/** 점검이 OK일 때 매번 부른다. 이미 비어 있으면 0행이라 쓰기가 일어나지 않는다. */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update StudentSession s set s.lastEscalatedSignature = null"
			+ " where s.id = :id and s.lastEscalatedSignature is not null")
	int clearLastEscalatedSignature(@Param("id") long id);

	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update StudentSession s set s.hostPort = :port, s.containerRunning = :running where s.id = :id")
	int updateContainerState(@Param("id") long id, @Param("port") int port, @Param("running") boolean running);
}
