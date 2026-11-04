package com.swunion.lab.course;

/** operator는 null(버전 미지정), "==", ">=" 중 하나. */
public record PackageSpec(String name, String operator, String version) {

	/** requirement 문자열. 공백 없이 이어 붙인다. */
	public String toLine() {
		return operator == null ? name : name + operator + version;
	}
}
