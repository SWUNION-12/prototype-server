package com.swunion.lab.agent;

import static com.swunion.lab.agent.AgentFakes.MISSING_SIX;
import static com.swunion.lab.agent.AgentFakes.OK;
import static org.assertj.core.api.Assertions.assertThat;

import com.swunion.lab.agent.AgentFakes.FakeChecker;
import com.swunion.lab.course.Course;
import com.swunion.lab.session.StudentSession;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AgentTriggerTest {

	/** 실행을 붙잡아 두는 가짜 러너. */
	static class BlockingRunner extends AgentRunner {
		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final List<AgentContext> seen = new CopyOnWriteArrayList<>();

		BlockingRunner() {
			super(null, null, null, null, null, null, null);
		}

		@Override
		public AgentAction run(AgentContext ctx) {
			seen.add(ctx);
			started.countDown();
			try {
				release.await(5, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return null;
		}
	}

	BlockingRunner runner = new BlockingRunner();
	FakeChecker checker = new FakeChecker();
	AgentTrigger trigger = new AgentTrigger(runner, checker);

	@AfterEach
	void tearDown() {
		runner.release.countDown();
		trigger.shutdown();
	}

	private StudentSession session(long id) {
		var s = new StudentSession(new Course("수업", "3.12", "six==1.16.0"), "학생", "t" + id, "pw");
		ReflectionTestUtils.setField(s, "id", id);
		s.setContainerId("c" + id);
		return s;
	}

	@Test
	void oneRunPerSessionAndHelpBuildsContextFromFreshCheck() throws Exception {
		StudentSession s = session(1);
		checker.current = OK;

		assertThat(trigger.onHelp(s, 5L, "ValueError", "ValueError: bad")).isTrue();
		assertThat(runner.started.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(trigger.isRunning(1)).isTrue();
		assertThat(trigger.isRunning(2)).isFalse();

		// 실행 중에는 도움 요청도 점검 불일치도 새 실행을 만들지 않는다
		assertThat(trigger.onHelp(s, 6L, null, null)).isFalse();
		trigger.onCheckMismatch(s, MISSING_SIX);

		runner.release.countDown();
		for (int i = 0; i < 100 && trigger.isRunning(1); i++) {
			Thread.sleep(50);
		}
		assertThat(trigger.isRunning(1)).isFalse();
		assertThat(runner.seen).singleElement().satisfies(ctx -> {
			assertThat(ctx.trigger()).isEqualTo(Trigger.HELP);
			assertThat(ctx.lastCheck()).isEqualTo(OK);
			assertThat(ctx.helpRequestId()).isEqualTo(5L);
			assertThat(ctx.errorType()).isEqualTo("ValueError");
		});
		assertThat(checker.checkCount).isEqualTo(1);
	}

	@Test
	void onHelpReturnsImmediately() {
		long t0 = System.nanoTime();
		assertThat(trigger.onHelp(session(3), 1L, null, null)).isTrue();
		assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(1000);
	}

	@Test
	void longSignaturesAreHashedToFitTheColumn() {
		String shortSig = "MISSING:six";
		assertThat(AgentTrigger.signatureKey(shortSig)).isEqualTo(shortSig);
		String longSig = "MISSING:pkg".repeat(500);
		String key = AgentTrigger.signatureKey(longSig);
		assertThat(key).startsWith("sha256:").hasSizeLessThan(100);
		assertThat(AgentTrigger.signatureKey(longSig)).isEqualTo(key);
	}

	@Test
	void helpStillRunsWhenPreCheckThrows() throws Exception {
		FakeChecker broken = new FakeChecker() {
			@Override
			public com.swunion.lab.check.CheckResult check(StudentSession session) {
				throw new IllegalStateException("docker daemon down");
			}
		};
		AgentTrigger t = new AgentTrigger(runner, broken);
		try {
			runner.release.countDown();
			assertThat(t.onHelp(session(4), 9L, null, "boom")).isTrue();
			for (int i = 0; i < 100 && t.isRunning(4); i++) {
				Thread.sleep(50);
			}
			assertThat(t.isRunning(4)).isFalse();
			assertThat(runner.seen).singleElement().satisfies(ctx -> {
				assertThat(ctx.lastCheck()).isNull();
				assertThat(ctx.helpRequestId()).isEqualTo(9L);
			});
		}
		finally {
			t.shutdown();
		}
	}

	@Test
	void exceptionInsideRunClearsRunningAndReportsFailure() throws Exception {
		List<AgentContext> reported = new CopyOnWriteArrayList<>();
		AgentRunner throwing = new AgentRunner(null, null, null, null, null, null, null) {
			@Override
			public AgentAction run(AgentContext ctx) {
				throw new IllegalStateException("boom");
			}

			@Override
			public AgentAction reportFailure(AgentContext ctx, String detail) {
				reported.add(ctx);
				return null;
			}
		};
		AgentTrigger t = new AgentTrigger(throwing, checker);
		try {
			assertThat(t.onHelp(session(5), 11L, null, "x")).isTrue();
			for (int i = 0; i < 100 && (t.isRunning(5) || reported.isEmpty()); i++) {
				Thread.sleep(50);
			}
			assertThat(t.isRunning(5)).isFalse();
			assertThat(reported).singleElement().satisfies(ctx -> assertThat(ctx.helpRequestId()).isEqualTo(11L));
			// 정리가 됐으니 같은 세션의 다음 요청을 받는다
			assertThat(t.onHelp(session(5), 12L, null, "y")).isTrue();
		}
		finally {
			t.shutdown();
		}
	}
}
