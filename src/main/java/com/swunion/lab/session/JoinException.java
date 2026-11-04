package com.swunion.lab.session;

/** 학생에게 그대로 보여줄 한국어 메시지를 담는다. */
public class JoinException extends RuntimeException {

	private final int status;

	public JoinException(String messageKo) {
		this(messageKo, 400);
	}

	public JoinException(String messageKo, int status) {
		super(messageKo);
		this.status = status;
	}

	public int status() {
		return status;
	}
}
