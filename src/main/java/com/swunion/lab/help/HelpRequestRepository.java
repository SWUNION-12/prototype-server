package com.swunion.lab.help;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface HelpRequestRepository extends JpaRepository<HelpRequest, Long> {

	Optional<HelpRequest> findFirstBySessionIdOrderByIdDesc(long sessionId);

	/** 대시보드의 반복 오류 유형별 집계용. */
	List<HelpRequest> findBySessionCourseIdAndErrorTypeIsNotNull(long courseId);

	// 에이전트 스레드와 학생 요청이 같은 행을 다루므로 필요한 컬럼만 갱신한다.
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update HelpRequest h set h.status = :status, h.explanation = :explanation, h.escalated = case when h.escalated = true then true else :escalated end"
			+ " where h.id = :id")
	int updateResult(@Param("id") long id, @Param("status") HelpStatus status,
			@Param("explanation") String explanation, @Param("escalated") boolean escalated);

	/** before보다 먼저 만들어진 from 상태 요청을 to로 닫는다. 서버 재시작 정리용(HelpRecovery). */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update HelpRequest h set h.status = :to, h.explanation = :explanation"
			+ " where h.status = :from and h.createdAt < :before")
	int closeStatusBefore(@Param("from") HelpStatus from, @Param("to") HelpStatus to,
			@Param("explanation") String explanation, @Param("before") Instant before);

	/** 처리 중에 눌러도 남는다. updateResult가 escalated를 내리지 않기 때문이다. */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update HelpRequest h set h.escalated = true where h.id = :id")
	int markEscalated(@Param("id") long id);

	// 학생·수업 삭제(LifecycleRows)가 세션 행보다 먼저 부른다(외래 키).

	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from HelpRequest h where h.session.id = :sessionId")
	int deleteBySessionId(@Param("sessionId") long sessionId);

	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from HelpRequest h where h.session.id in"
			+ " (select s.id from StudentSession s where s.course.id = :courseId)")
	int deleteByCourseId(@Param("courseId") long courseId);
}
