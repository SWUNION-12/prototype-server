package com.swunion.lab.help;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 에이전트 실행 도중 서버가 꺼지면 그 도움 요청은 결과를 채울 실행이 없어 영영 PROCESSING("분석 중")에 남는다.
 * 시작할 때 NO_ACTION으로 닫고 학생에게 다시 요청하라고 알린다. CourseRecovery와 같은 방식이다.
 */
@Component
public class HelpRecovery {

	private static final Logger log = LoggerFactory.getLogger(HelpRecovery.class);
	static final String INTERRUPTED = "서버가 다시 시작되어 처리가 중단됐습니다. 다시 요청해 주세요.";

	private final HelpRequestRepository helps;
	// 웹 서버가 요청을 받기 전에 빈이 만들어진다. 이 시각 뒤에 들어온 요청은 지금 실행이 처리하므로 건드리지 않는다.
	private final Instant startedAt;

	@Autowired
	public HelpRecovery(HelpRequestRepository helps) {
		this(helps, Instant.now());
	}

	HelpRecovery(HelpRequestRepository helps, Instant startedAt) {
		this.helps = helps;
		this.startedAt = startedAt;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onReady() {
		closeInterruptedHelps();
	}

	int closeInterruptedHelps() {
		int closed = helps.closeStatusBefore(HelpStatus.PROCESSING, HelpStatus.NO_ACTION, INTERRUPTED, startedAt);
		if (closed > 0) {
			log.info("서버 재시작으로 멈춘 도움 요청 {}건을 NO_ACTION으로 닫았습니다.", closed);
		}
		return closed;
	}
}
