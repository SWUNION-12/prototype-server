package com.swunion.lab.agent;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AgentActionRepository extends JpaRepository<AgentAction, Long> {

	List<AgentAction> findBySession_IdOrderByIdAsc(long sessionId);

	/** 대시보드가 세션별 최근 기록만 고르려고 id와 세션 id만 읽는 가벼운 조회. 큰 toolLogJson은 고른 뒤에 읽는다. */
	interface Ref {
		Long getId();

		Long getSessionId();
	}

	@Query("select a.id as id, a.session.id as sessionId from AgentAction a"
			+ " where a.session.course.id = :courseId order by a.id desc")
	List<Ref> findRefsByCourseNewestFirst(@Param("courseId") long courseId);

	// 아래 둘은 학생·수업 삭제(LifecycleRows)가 세션 행보다 먼저 부른다(외래 키). 일괄 삭제는 조인을 쓸 수 없어
	// 수업 단위는 세션 id를 하위 질의로 고른다.

	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from AgentAction a where a.session.id = :sessionId")
	int deleteBySessionId(@Param("sessionId") long sessionId);

	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from AgentAction a where a.session.id in"
			+ " (select s.id from StudentSession s where s.course.id = :courseId)")
	int deleteByCourseId(@Param("courseId") long courseId);
}
