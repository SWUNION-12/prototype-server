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
}
