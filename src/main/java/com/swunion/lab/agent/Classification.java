package com.swunion.lab.agent;

/** finish가 받는 판단 유형. UNKNOWN은 모델이 finish를 부르지 못하고 끝난 경우에만 서버가 쓴다. */
public enum Classification {
	ENV, CODE, OUT_OF_SCOPE, UNKNOWN
}
