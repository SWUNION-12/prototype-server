package com.swunion.lab.agent;

/** 서버가 재점검 뒤에 정하는 최종 처리. 모델의 주장으로 정하지 않는다. */
public enum Outcome {
	RESOLVED, EXPLAINED, ESCALATED, NO_ACTION
}
