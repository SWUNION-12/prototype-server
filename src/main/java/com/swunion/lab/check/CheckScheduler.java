package com.swunion.lab.check;

import com.swunion.lab.agent.AgentTrigger;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.session.SessionRepository;
import com.swunion.lab.session.StudentSession;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 테스트가 실제 Docker를 건드리지 않도록 lab.check-enabled=false로 끌 수 있다(기본 켜짐). */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "lab.check-enabled", havingValue = "true", matchIfMissing = true)
public class CheckScheduler {

	private static final Logger log = LoggerFactory.getLogger(CheckScheduler.class);

	private final SessionRepository sessions;
	private final EnvironmentChecker checker;
	private final AgentTrigger agent;

	public CheckScheduler(SessionRepository sessions, EnvironmentChecker checker, AgentTrigger agent) {
		this.sessions = sessions;
		this.checker = checker;
		this.agent = agent;
	}

	@Scheduled(fixedDelayString = "${lab.check-interval-seconds}", timeUnit = TimeUnit.SECONDS)
	public void runOnce() {
		for (StudentSession s : sessions.findByCourse_StatusAndContainerIdIsNotNull(CourseStatus.READY)) {
			if (agent.isRunning(s.getId())) {
				// 실행 중인 세션은 에이전트가 끝에 직접 재점검한다. 여기서 기록하면 REPAIRING을 덮는다.
				continue;
			}
			try {
				CheckResult result = checker.checkAndRecord(s);
				afterCheck(s, result);
			}
			catch (RuntimeException e) {
				// 한 세션의 실패가 나머지 점검을 막지 않게 한다.
				log.warn("세션 점검 실패 session={}", s.getId(), e);
			}
		}
	}

	/**
	 * 불일치면 에이전트를 부른다. 같은 불일치로 이미 강사에게 넘긴 세션은 다시 부르지 않는다
	 * (LLM 비용과 무한 반복 방지). 점검이 OK가 되면 그 기록을 지워 다음 불일치는 다시 부르게 한다.
	 */
	protected void afterCheck(StudentSession session, CheckResult result) {
		long id = session.getId();
		if (result.ok()) {
			sessions.clearLastEscalatedSignature(id);
			return;
		}
		if (agent.isRunning(id)) {
			return;
		}
		// 틱 시작에 읽은 엔티티는 그 사이 끝난 실행이 저장한 서명을 모를 수 있어 다시 읽는다.
		StudentSession latest = sessions.findById(id).orElse(null);
		if (latest == null) {
			return;
		}
		if (AgentTrigger.signatureKey(result.signature()).equals(latest.getLastEscalatedSignature())) {
			return;
		}
		agent.onCheckMismatch(latest, result);
	}
}
