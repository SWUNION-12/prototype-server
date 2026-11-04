package com.swunion.lab.help;

public class HelpBusyException extends RuntimeException {

	public HelpBusyException() {
		super("에이전트가 처리 중입니다. 잠시 후 다시 시도해 주세요");
	}
}
