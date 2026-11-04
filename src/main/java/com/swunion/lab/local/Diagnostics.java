package com.swunion.lab.local;

/** 학생이 붙여넣은 조회 결과 중 형식 검사를 통과한 값만 담는다. */
public record Diagnostics(String os, String arch, String python) {
}
