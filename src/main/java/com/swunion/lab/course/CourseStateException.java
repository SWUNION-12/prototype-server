package com.swunion.lab.course;

/** 지금 상태에서는 할 수 없는 요청(예: 아직 발급 중이거나 발급에 실패한 수업을 종료). 컨트롤러가 409로 바꾼다. */
public class CourseStateException extends RuntimeException {

	public CourseStateException(String messageKo) {
		super(messageKo);
	}
}
