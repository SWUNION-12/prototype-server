package com.swunion.lab.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swunion.lab.local.LocalControllerTest.MutableClock;
import com.swunion.lab.session.StudentSession;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DownloadCodesTest {

	static final Instant T0 = Instant.parse("2026-11-05T09:00:00Z");

	final MutableClock clock = new MutableClock(T0);
	final DownloadCodes codes = new DownloadCodes(clock);

	private static StudentSession session(long id, String token) {
		StudentSession s = mock(StudentSession.class);
		when(s.getId()).thenReturn(id);
		when(s.getToken()).thenReturn(token);
		return s;
	}

	@Test
	void codeIsRandom32BytesUnrelatedToTheToken() {
		String token = "ab".repeat(32);
		StudentSession s = session(1, token);
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 5; i++) {
			DownloadCodes.Issued issued = codes.issue(s);
			assertThat(issued.code()).matches("[0-9a-f]{64}").isNotEqualTo(token);
			assertThat(issued.expiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(10)));
			seen.add(issued.code());
		}
		assertThat(seen).hasSize(5);
	}

	@Test
	void usableManyTimesUntilTenMinutesThenGone() {
		String code = codes.issue(session(1, "tok-1")).code();

		assertThat(codes.tokenFor(code)).isEqualTo("tok-1");
		clock.set(T0.plus(Duration.ofMinutes(10)).minusMillis(1));
		assertThat(codes.tokenFor(code)).isEqualTo("tok-1");

		clock.set(T0.plus(Duration.ofMinutes(10)));
		assertThatThrownBy(() -> codes.tokenFor(code)).isInstanceOf(DownloadCodeExpiredException.class);
		assertThat(codes.size()).as("만료를 확인한 코드는 버린다").isZero();
	}

	@Test
	void unknownBlankAndRevokedCodesAreRejected() {
		assertThatThrownBy(() -> codes.tokenFor("nope")).isInstanceOf(DownloadCodeExpiredException.class);
		assertThatThrownBy(() -> codes.tokenFor(null)).isInstanceOf(DownloadCodeExpiredException.class);
		String code = codes.issue(session(1, "tok-1")).code();
		codes.revoke(code);
		assertThatThrownBy(() -> codes.tokenFor(code)).isInstanceOf(DownloadCodeExpiredException.class);
	}

	@Test
	void expiredCodesAreDroppedWhenNewOnesAreIssued() {
		codes.issue(session(1, "tok-1"));
		codes.issue(session(2, "tok-2"));
		clock.set(T0.plus(Duration.ofMinutes(11)));

		codes.issue(session(3, "tok-3"));

		assertThat(codes.size()).isEqualTo(1);
	}

	// 화면을 계속 다시 열어도 한 학생의 코드는 일정 수를 넘지 않는다. 가장 오래된 것부터 버리고 다른 학생 것은 건드리지 않는다.
	@Test
	void eachSessionKeepsOnlyItsNewestCodes() {
		String other = codes.issue(session(2, "tok-2")).code();
		StudentSession s = session(1, "tok-1");
		String oldest = codes.issue(s).code();
		for (int i = 0; i < DownloadCodes.MAX_PER_SESSION; i++) {
			clock.set(clock.instant().plusSeconds(1));
			codes.issue(s);
		}

		assertThat(codes.size()).isEqualTo(DownloadCodes.MAX_PER_SESSION + 1);
		assertThatThrownBy(() -> codes.tokenFor(oldest)).isInstanceOf(DownloadCodeExpiredException.class);
		assertThat(codes.tokenFor(other)).isEqualTo("tok-2");
	}
}
