package com.swunion.lab.agent;

import com.swunion.lab.check.CheckResult;
import com.swunion.lab.check.EnvironmentChecker;
import com.swunion.lab.session.StudentSession;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 에이전트 실행의 입구. 세션마다 한 번에 하나만 돌고, 호출자는 기다리지 않는다.
 * isRunning은 접수한 순간부터 재점검·저장·이벤트 처리까지 끝날 때까지 true다.
 */
@Component
public class AgentTrigger {

	private static final Logger log = LoggerFactory.getLogger(AgentTrigger.class);
	static final int POOL_SIZE = 4;
	// lastEscalatedSignature 컬럼은 4,000자다. 패키지가 많은 수업에서 venv가 통째로 깨지면 서명이 그보다 길어진다.
	static final int MAX_SIGNATURE = 1000;

	private final AgentRunner runner;
	private final EnvironmentChecker checker;
	private final Set<Long> running = ConcurrentHashMap.newKeySet();
	private final ExecutorService pool;

	public AgentTrigger(AgentRunner runner, EnvironmentChecker checker) {
		this.runner = runner;
		this.checker = checker;
		AtomicInteger n = new AtomicInteger();
		this.pool = Executors.newFixedThreadPool(POOL_SIZE, r -> {
			Thread t = new Thread(r, "agent-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
	}

	/** 이미 실행 중이면 조용히 무시한다. 점검은 다음 주기에 다시 본다. */
	public void onCheckMismatch(StudentSession session, CheckResult result) {
		submit(session.getId(), () -> runner.run(new AgentContext(session, Trigger.CHECK, result, null, null, null)));
	}

	/** @return 이미 실행 중이라 받지 않았으면 false (도움 요청은 409로 돌려준다) */
	public boolean onHelp(StudentSession session, long helpRequestId, String errorType, String errorText) {
		// 학생이 누른 시점의 환경을 모델에게 보여 주려고 실행 스레드에서 점검부터 한다(기록하지 않음).
		return submit(session.getId(), () -> runner.run(new AgentContext(session, Trigger.HELP,
				checker.check(session), errorType, errorText, helpRequestId)));
	}

	public boolean isRunning(long sessionId) {
		return running.contains(sessionId);
	}

	private boolean submit(long sessionId, Runnable task) {
		if (!running.add(sessionId)) {
			return false;
		}
		try {
			pool.execute(() -> {
				try {
					task.run();
				}
				catch (RuntimeException e) {
					log.error("에이전트 실행 실패 session={}", sessionId, e);
				}
				finally {
					running.remove(sessionId);
				}
			});
			return true;
		}
		catch (RejectedExecutionException e) {
			running.remove(sessionId);
			log.warn("에이전트 실행을 접수하지 못함 session={}", sessionId);
			return false;
		}
	}

	/**
	 * lastEscalatedSignature에 저장하고 비교하는 값. 짧은 서명은 그대로, 긴 서명은 해시로 줄인다.
	 * 저장하는 쪽(AgentRunner)과 비교하는 쪽(CheckScheduler)이 모두 이 함수를 거쳐야 한다.
	 */
	public static String signatureKey(String signature) {
		if (signature == null || signature.length() <= MAX_SIGNATURE) {
			return signature;
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(signature.getBytes(StandardCharsets.UTF_8));
			return "sha256:" + HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	@PreDestroy
	void shutdown() {
		pool.shutdownNow();
	}
}
