package com.swunion.lab.agent;

import com.swunion.lab.check.CheckResult;
import com.swunion.lab.session.StudentSession;

/**
 * 한 번의 에이전트 실행에 넘기는 입력. errorText는 여기서 4,000자로 자른다.
 * 어느 경로로 만들어도 LLM에 4,000자를 넘는 학생 출력이 가지 않게 하기 위함이다.
 */
public record AgentContext(StudentSession session, Trigger trigger, CheckResult lastCheck, String errorType,
		String errorText, Long helpRequestId) {

	public static final int MAX_ERROR_TEXT = 4000;

	public AgentContext {
		errorText = truncate(errorText, MAX_ERROR_TEXT);
	}

	/** 서로게이트 쌍 가운데를 자르지 않는다. */
	static String truncate(String s, int max) {
		if (s == null || s.length() <= max) {
			return s;
		}
		int end = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
		return s.substring(0, end);
	}
}
