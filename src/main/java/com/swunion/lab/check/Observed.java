package com.swunion.lab.check;

import java.util.Map;

/** 컨테이너에서 관찰한 원본 값. null은 "조회 실패"를 뜻한다. */
public record Observed(boolean running, String pythonVersion, Map<String, String> installed, int ideConnections) {
}
