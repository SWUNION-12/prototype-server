package com.swunion.lab.help;

/** 종료된 수업의 학생 요청. 컨트롤러가 409로 바꾼다. */
public class CourseEndedException extends RuntimeException {

	public CourseEndedException() {
		super("종료된 수업입니다");
	}
}
