package com.swunion.lab.agent;

/** 실행 결과를 저장한 뒤 발행한다. helpRequestId는 HELP 실행일 때만 있다. */
public record AgentFinishedEvent(AgentAction action, Long helpRequestId) {
}
