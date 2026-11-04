package com.swunion.lab.agent;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
