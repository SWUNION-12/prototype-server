package com.swunion.lab.agent;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentActionRepository extends JpaRepository<AgentAction, Long> {

	List<AgentAction> findBySession_IdOrderByIdAsc(long sessionId);
}
