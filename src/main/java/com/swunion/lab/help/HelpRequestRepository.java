package com.swunion.lab.help;

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
	@Query("update HelpRequest h set h.status = :status, h.explanation = :explanation, h.escalated = :escalated"
			+ " where h.id = :id")
	int updateResult(@Param("id") long id, @Param("status") HelpStatus status,
			@Param("explanation") String explanation, @Param("escalated") boolean escalated);

	/** 에이전트가 아직 도는 요청은 건드리지 않는다. 결과 갱신이 escalated를 덮어쓰기 때문이다. */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update HelpRequest h set h.escalated = true where h.id = :id and h.status <> 'PROCESSING'")
	int markEscalated(@Param("id") long id);
}
