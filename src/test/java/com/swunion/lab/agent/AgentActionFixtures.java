package com.swunion.lab.agent;

import com.swunion.lab.session.StudentSession;
import java.time.Instant;

/** AgentAction의 생성자와 setter가 패키지 전용이라, 다른 패키지의 테스트가 행을 만들 때 쓴다. */
public final class AgentActionFixtures {

	private AgentActionFixtures() {
	}

	public static AgentAction action(StudentSession session, Trigger trigger, Classification classification,
			Outcome outcome, String toolLogJson, String summary, Instant startedAt, Instant finishedAt) {
		AgentAction a = new AgentAction(session, trigger, startedAt);
		a.setClassification(classification);
		a.setOutcome(outcome);
		a.setToolLogJson(toolLogJson);
		a.setRejectedJson("[]");
		a.setSummary(summary);
		a.setFinishedAt(finishedAt);
		return a;
	}
}
