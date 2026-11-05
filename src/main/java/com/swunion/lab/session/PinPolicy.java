package com.swunion.lab.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 학생 PIN의 형식, 해시, 잠금 규칙. 4자리 PIN은 경우의 수가 1만 개뿐이라 잠금이 무작위 대입을 막는 유일한 방어선이다.
 * PIN과 해시는 어떤 메시지에도 싣지 않는다.
 */
@Component
public class PinPolicy {

	public static final int MAX_FAILURES = 5;
	public static final Duration LOCK = Duration.ofMinutes(5);
	private static final Pattern FORMAT = Pattern.compile("[0-9]{4}");

	// 보안 설정의 DelegatingPasswordEncoder와 달리 "{bcrypt}" 접두사 없이 순수 BCrypt 해시를 저장한다.
	private final PasswordEncoder encoder = new BCryptPasswordEncoder();
	private final Clock clock;

	public PinPolicy(Clock clock) {
		this.clock = clock;
	}

	/** 숫자 정확히 4자리가 아니면 400. 공백도 다듬지 않는다(정한 PIN과 다른 값이 저장되지 않게). */
	public void validate(String pin) {
		if (pin == null || !FORMAT.matcher(pin).matches()) {
			throw new JoinException("PIN은 숫자 4자리로 입력해 주세요.");
		}
	}

	public String hash(String pin) {
		return encoder.encode(pin);
	}

	public boolean matches(String pin, String hash) {
		return pin != null && hash != null && encoder.matches(pin, hash);
	}

	/** @return 지금 잠겨 있으면 남은 시간, 아니면 null(잠긴 적 없거나 이미 풀림) */
	Duration lockRemaining(Instant lockedUntil) {
		if (lockedUntil == null) {
			return null;
		}
		Duration left = Duration.between(clock.instant(), lockedUntil);
		return left.isPositive() ? left : null;
	}

	/** 이번 실패로 MAX_FAILURES번에 닿으면 이 시각까지 잠근다. 다섯 번째 실패 시각부터 LOCK만큼이다. */
	Instant lockUntilFromNow() {
		return clock.instant().plus(LOCK);
	}

	/** @param failures 이번 실패까지 센 연속 실패 횟수 */
	JoinException wrongPin(int failures) {
		int left = MAX_FAILURES - failures;
		if (left > 0) {
			return new JoinException("PIN이 맞지 않습니다. 남은 기회 " + left + "번", 403);
		}
		return new JoinException("PIN이 맞지 않습니다. " + LOCK.toMinutes() + "분 뒤 다시 시도하세요", 403);
	}

	JoinException locked(Duration remaining) {
		return new JoinException("PIN을 너무 많이 틀렸습니다. " + minutesRoundedUp(remaining) + "분 뒤 다시 시도하세요", 429);
	}

	// 4분 1초 남았을 때 "4분 뒤"라고 하면 학생이 그때 다시 와서 또 거절된다.
	static long minutesRoundedUp(Duration d) {
		long minutes = d.toMinutes();
		return d.equals(Duration.ofMinutes(minutes)) ? minutes : minutes + 1;
	}
}
