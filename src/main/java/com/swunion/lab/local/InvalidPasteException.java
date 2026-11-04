package com.swunion.lab.local;

/** 학생이 붙여넣은 텍스트가 정해진 형식이 아닐 때. 메시지는 학생에게 그대로 보여준다. */
public class InvalidPasteException extends RuntimeException {

	public InvalidPasteException(String messageKo) {
		super(messageKo);
	}
}
