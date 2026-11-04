package com.swunion.lab.local;

import java.util.List;

/** python은 설치 단계에서 실패하면 null, 이때 passed와 total은 0. failed 항목은 "이름:Reason". */
public record VerifyResult(boolean ok, String python, int passed, int total, List<String> failed) {
}
