package com.swunion.lab.session;

import java.time.Duration;

/** 단위 테스트가 실제로 기다리지 않도록 분리한 IDE 기동 대기. */
public interface HealthWaiter {

	/** 127.0.0.1:{hostPort}/healthz 가 200을 줄 때까지 기다린다. */
	boolean awaitHealthy(int hostPort, Duration timeout);
}
