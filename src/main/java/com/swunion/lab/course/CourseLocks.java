package com.swunion.lab.course;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 수업 단위 작업(종료, 재개, 삭제, 학생 삭제)을 수업마다 한 번에 하나만 돌린다. 이 서버 메모리 안에서만 통한다.
 * 겹친 요청은 잠깐만 기다리고 409로 돌려보낸다. 재개와 삭제는 몇십 초 걸릴 수 있어, 줄을 세우면 HTTP 스레드가
 * 그동안 묶이고 강사는 같은 버튼을 또 누르게 된다.
 */
@Component
public class CourseLocks {

	static final Duration DEFAULT_WAIT = Duration.ofSeconds(2);
	static final String BUSY_MESSAGE = "다른 작업이 진행 중입니다. 잠시 후 다시 시도해 주세요.";

	// 수업마다 하나이고 지우지 않는다. 지운 수업의 잠금을 맵에서 빼면, 그 잠금을 기다리던 요청과 새로 만든 잠금을
	// 쥔 요청이 동시에 돌 수 있다. 수업 수만큼만 쌓인다.
	private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();
	private final Duration wait;

	@Autowired
	public CourseLocks() {
		this(DEFAULT_WAIT);
	}

	/** 테스트가 기다림을 줄이거나 늘린다. */
	public CourseLocks(Duration wait) {
		this.wait = wait;
	}

	/**
	 * @throws CourseStateException 같은 수업의 다른 작업이 wait 안에 끝나지 않았다(409)
	 */
	public <T> T run(long courseId, Supplier<T> work) {
		ReentrantLock lock = locks.computeIfAbsent(courseId, id -> new ReentrantLock());
		boolean acquired;
		try {
			acquired = lock.tryLock(wait.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CourseStateException(BUSY_MESSAGE);
		}
		if (!acquired) {
			throw new CourseStateException(BUSY_MESSAGE);
		}
		try {
			return work.get();
		}
		finally {
			lock.unlock();
		}
	}
}
