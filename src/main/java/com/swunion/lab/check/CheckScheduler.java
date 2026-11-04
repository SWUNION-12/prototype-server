package com.swunion.lab.check;

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

	public CheckScheduler(SessionRepository sessions, EnvironmentChecker checker) {
		this.sessions = sessions;
		this.checker = checker;
	}

	@Scheduled(fixedDelayString = "${lab.check-interval-seconds}", timeUnit = TimeUnit.SECONDS)
	public void runOnce() {
		for (StudentSession s : sessions.findByCourse_StatusAndContainerIdIsNotNull(CourseStatus.READY)) {
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
	 * 훅 지점. 에이전트 태스크가 여기서 "불일치면 에이전트 호출"과
	 * "이미 실행 중인 세션은 건너뛰기"를 처리한다. 지금은 기록만 한다.
	 */
	protected void afterCheck(StudentSession session, CheckResult result) {
	}
}
