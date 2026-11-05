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

	/** 강사 대시보드가 수업의 학생을 한 번에 읽는다. */
	List<StudentSession> findByCourse_Id(long courseId);

	/** 수업 종료가 멈출 컨테이너를 찾는다. */
	List<StudentSession> findByCourse_IdAndContainerIdIsNotNull(long courseId);

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

	/**
	 * 강사 도움 필요를 켜고 이유를 덧붙인다. 비어 있으면 reason만, 이미 reason이 들어 있거나 덧붙이면 컬럼(4,000자)을
	 * 넘는 길이(maxKeep 초과)면 그대로 둔다. 읽고 쓰는 사이에 에이전트가 이유를 바꿔도 잃지 않게 한 문장으로 한다.
	 */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update StudentSession s set s.needsHelp = true, s.needsHelpReason = case"
			+ " when s.needsHelpReason is null or s.needsHelpReason = '' then :reason"
			+ " when locate(:reason, s.needsHelpReason) > 0 or length(s.needsHelpReason) > :maxKeep"
			+ " then s.needsHelpReason"
			+ " else concat(s.needsHelpReason, ' / ', :reason) end where s.id = :id")
	int appendNeedsHelpReason(@Param("id") long id, @Param("reason") String reason, @Param("maxKeep") int maxKeep);

	/** StudentSession.needsHelpReason 컬럼 길이 */
	int NEEDS_HELP_REASON_LENGTH = 4000;

	/** appendNeedsHelpReason에 컬럼 길이에서 구분자(" / ")와 새 이유 길이를 뺀 maxKeep을 계산해 넘긴다. */
	default int addNeedsHelpReason(long id, String reason) {
		return appendNeedsHelpReason(id, reason, NEEDS_HELP_REASON_LENGTH - " / ".length() - reason.length());
	}

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

	/** join이 컨테이너를 만든 직후. 이때부터 점검이 이 행을 읽고 쓰므로 join도 자기 컬럼만 쓴다. */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update StudentSession s set s.containerId = :containerId, s.hostPort = :port, s.containerRunning = true"
			+ " where s.id = :id")
	int updateContainerCreated(@Param("id") long id, @Param("containerId") String containerId, @Param("port") int port);

	/** code-server 응답을 기다리는 동안(최대 60초) 점검·에이전트가 쓴 값을 덮지 않도록 기동 시간만 쓴다. */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update StudentSession s set s.startupMillis = :millis where s.id = :id")
	int updateStartupMillis(@Param("id") long id, @Param("millis") long millis);

	/** 수업 종료 뒤에는 점검이 돌지 않으므로, 멈춘 컨테이너를 대시보드가 실행 중으로 보이지 않게 직접 내린다. */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update StudentSession s set s.containerRunning = false, s.ideConnected = false where s.id = :id")
	int markStopped(@Param("id") long id);

	/**
	 * 볼륨이 사라진 것을 처음 알았을 때만 시각을 기록한다. 학생 화면과 강사 대시보드가 이 값으로 유실을 안다.
	 * 이미 기록됐으면 0행이다. 호출자는 이 값이 1일 때만 강사 도움 필요 이유를 덧붙여, 같은 유실이 다시 확인돼도
	 * 강사가 이미 치운 표시가 되살아나지 않게 한다.
	 * @return 새로 기록했으면 1, 이미 기록돼 있거나 세션이 없으면 0
	 */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update StudentSession s set s.workspaceLostAt = :at where s.id = :id and s.workspaceLostAt is null")
	int markWorkspaceLost(@Param("id") long id, @Param("at") Instant at);

	// 아래는 PIN 입장·되찾기의 쓰기다. 행을 넣은 순간부터 같은 이름의 다른 입장, 점검, 에이전트가 같은 행을 쓰므로
	// 전부 자기 컬럼만 바꾼다.

	/** join이 행을 넣은 직후 볼륨(=컨테이너) 이름을 정한다. 이름에 세션 id가 들어가 insert 뒤에야 알 수 있다. */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update StudentSession s set s.volumeName = :volume where s.id = :id")
	int updateVolumeName(@Param("id") long id, @Param("volume") String volume);

	/** 되찾기 성공 뒤 예전 토큰을 끊는다. 학생 화면·설치 링크가 모두 토큰으로 세션을 찾으므로 함께 끊긴다. */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update StudentSession s set s.token = :token where s.id = :id")
	int rotateToken(@Param("id") long id, @Param("token") String token);

	/** 이 기능 전에 만든 행이 처음 되찾아질 때 이름 키를 채운다. 이미 있으면 그대로 둔다. */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update StudentSession s set s.nameKey = :key where s.id = :id and s.nameKey is null")
	int setNameKey(@Param("id") long id, @Param("key") String key);

	/** PIN이 비어 있던 세션(초기화됐거나 이 기능 전에 만든 세션)에 받은 PIN을 정한다. 실패 기록도 비운다. */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update StudentSession s set s.pinHash = :hash, s.failedPinAttempts = 0, s.pinLockedUntil = null"
			+ " where s.id = :id")
	int setPin(@Param("id") long id, @Param("hash") String hash);

	/**
	 * PIN 실패를 하나 올리고, 이번 실패로 MAX_FAILURES번에 닿으면 lockUntil까지 잠근다. 읽고 쓰는 사이에 다른 실패가
	 * 끼어도 횟수를 잃거나 잠금을 놓치지 않게 한 문장으로 한다. SET 오른쪽의 failedPinAttempts는 갱신 전 값이다.
	 */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update StudentSession s set s.failedPinAttempts = s.failedPinAttempts + 1,"
			+ " s.pinLockedUntil = case when s.failedPinAttempts + 1 >= " + PinPolicy.MAX_FAILURES
			+ " then :lockUntil else s.pinLockedUntil end where s.id = :id")
	int recordPinFailure(@Param("id") long id, @Param("lockUntil") Instant lockUntil);

	/** PIN이 맞았거나 잠금이 풀렸을 때. 이미 비어 있으면 0행이라 쓰기가 일어나지 않는다. */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update StudentSession s set s.failedPinAttempts = 0, s.pinLockedUntil = null"
			+ " where s.id = :id and (s.failedPinAttempts <> 0 or s.pinLockedUntil is not null)")
	int clearPinFailures(@Param("id") long id);

	/** 강사의 PIN 초기화. 다음에 그 이름으로 들어오는 사람이 새 PIN을 정한다. */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update StudentSession s set s.pinHash = null, s.failedPinAttempts = 0, s.pinLockedUntil = null"
			+ " where s.id = :id")
	int resetPin(@Param("id") long id);

	/** 방금 기록한 실패 횟수. 엔티티 전체(점검 결과 JSON 등)를 읽지 않으려고 한 컬럼만 읽는다. */
	@Query("select s.failedPinAttempts from StudentSession s where s.id = :id")
	Optional<Integer> findFailedPinAttempts(@Param("id") long id);

	/** 수업 삭제(LifecycleRows). 하위 표의 행을 먼저 지운 뒤에 부른다(외래 키). */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from StudentSession s where s.course.id = :courseId")
	int deleteByCourseId(@Param("courseId") long courseId);
}
