package com.swunion.lab.local;

public class CodeUnavailableException extends RuntimeException {

	public CodeUnavailableException(String messageKo) {
		super(messageKo);
	}

	public CodeUnavailableException(String messageKo, Throwable cause) {
		super(messageKo, cause);
	}
}
