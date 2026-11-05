package com.swunion.lab.local;

import com.swunion.lab.session.StudentSession;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 터미널(curl, PowerShell)이 설치 스크립트·수업 파일·코드 zip을 받을 때 쓰는 짧은 다운로드 코드. 터미널에는 학생 쿠키가
 * 없으므로 주소에 무언가를 실어야 하는데, 세션 토큰 대신 이 코드를 싣는다. 주소가 새어도 10분 뒤에는 쓸 수 없다.
 * <p>
 * 코드는 발급 때의 세션 토큰을 가리키고, 쓸 때마다 그 토큰으로 세션을 다시 찾는다(LocalService.byCode). 그래서 재입장으로
 * 토큰이 바뀌거나 강사가 학생·수업을 지우면 남은 코드도 바로 쓸 수 없다. 서버 메모리에만 두므로 서버를 다시 켜면 모두
 * 사라지고 학생은 화면에서 다시 받는다. 설치 스크립트가 파일 여러 개를 받으므로 10분 안에는 여러 번 쓸 수 있다.
 */
@Component
public class DownloadCodes {

	static final Duration TTL = Duration.ofMinutes(10);
	/** 한 세션이 동시에 가질 수 있는 코드 수. 화면을 계속 다시 열어도 메모리가 늘지 않게 오래된 것부터 버린다. */
	static final int MAX_PER_SESSION = 8;

	public record Issued(String code, Instant expiresAt) {
	}

	private record Entry(long sessionId, String token, Instant expiresAt) {
	}

	private final Map<String, Entry> codes = new ConcurrentHashMap<>();
	private final SecureRandom random = new SecureRandom();
	private final Clock clock;

	public DownloadCodes(Clock clock) {
		this.clock = clock;
	}

	/** 무작위 32바이트(소문자 16진 64자) 코드를 만든다. 세션 토큰과는 무관한 값이다. */
	public synchronized Issued issue(StudentSession s) {
		Instant now = clock.instant();
		codes.values().removeIf(e -> !now.isBefore(e.expiresAt()));
		String code = newCode();
		Instant expiresAt = now.plus(TTL);
		codes.put(code, new Entry(s.getId(), s.getToken(), expiresAt));
		List<Map.Entry<String, Entry>> mine = codes.entrySet().stream()
				.filter(e -> e.getValue().sessionId() == s.getId())
				.sorted(Comparator.comparing((Map.Entry<String, Entry> e) -> e.getValue().expiresAt()))
				.toList();
		for (int i = 0; i < mine.size() - MAX_PER_SESSION; i++) {
			codes.remove(mine.get(i).getKey());
		}
		return new Issued(code, expiresAt);
	}

	/**
	 * @return 코드를 발급할 때의 세션 토큰. 호출자가 이 토큰으로 세션을 다시 찾아야 한다.
	 * @throws DownloadCodeExpiredException 모르는 코드이거나 10분이 지났다
	 */
	public String tokenFor(String code) {
		Entry e = code == null ? null : codes.get(code);
		if (e == null) {
			throw new DownloadCodeExpiredException();
		}
		if (!clock.instant().isBefore(e.expiresAt())) {
			codes.remove(code, e);
			throw new DownloadCodeExpiredException();
		}
		return e.token();
	}

	/** 세션이 사라졌거나 토큰이 바뀐 코드를 버린다. 어차피 다시 쓸 수 없으니 만료를 기다리지 않는다. */
	public void revoke(String code) {
		if (code != null) {
			codes.remove(code);
		}
	}

	int size() {
		return codes.size();
	}

	private String newCode() {
		byte[] b = new byte[32];
		random.nextBytes(b);
		return HexFormat.of().formatHex(b);
	}
}
