package com.swunion.lab.session;

public class SessionNotFoundException extends RuntimeException {

	public SessionNotFoundException() {
		super("session not found");
	}
}
