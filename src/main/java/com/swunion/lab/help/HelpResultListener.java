package com.swunion.lab.help;

import com.swunion.lab.agent.AgentAction;
import com.swunion.lab.agent.AgentFinishedEvent;
import com.swunion.lab.agent.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 에이전트가 끝나면 도움 요청에 최종 상태와 설명을 채운다. 발행 쪽이 트랜잭션이 아니라 @EventListener를 쓴다. */
@Component
public class HelpResultListener {

	private static final Logger log = LoggerFactory.getLogger(HelpResultListener.class);

	private final HelpRequestRepository helps;

	public HelpResultListener(HelpRequestRepository helps) {
		this.helps = helps;
	}

	@EventListener
	public void onFinished(AgentFinishedEvent event) {
		Long id = event.helpRequestId();
		if (id == null) {
			return;
		}
		// 실패 경로의 action은 저장 전이라 id가 null이다. 여기서는 id를 쓰지 않는다.
		AgentAction action = event.action();
		Outcome outcome = action == null ? null : action.getOutcome();
		HelpStatus status = outcome == null ? HelpStatus.NO_ACTION : HelpStatus.valueOf(outcome.name());
		String explanation = action == null ? null : action.getExplanation();
		int rows = helps.updateResult(id, status, explanation, outcome == Outcome.ESCALATED);
		if (rows == 0) {
			log.warn("도움 요청 {}이(가) 없어 결과를 기록하지 못했습니다.", id);
		}
	}
}
