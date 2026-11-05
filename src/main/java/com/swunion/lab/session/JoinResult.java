package com.swunion.lab.session;

/** @param resumed 같은 이름과 PIN으로 원래 세션에 돌아왔으면 true, 새로 만들었으면 false */
public record JoinResult(StudentSession session, boolean resumed) {
}
