package com.swunion.lab.session;

import java.util.Locale;

/**
 * 학생 IDE(code-server)를 누가 지키는가. 설정 lab.ide-auth(IDE_AUTH)로 정하고, 컨테이너를 만들 때의 값을 세션에 남긴다.
 * <ul>
 * <li>PASSWORD: code-server가 비밀번호를 묻는다. 학생 화면이 비밀번호를 보여 준다. Caddy 없는 로컬·LAN 시연용.</li>
 * <li>PROXY: code-server는 비밀번호 없이 돌고, 앞의 Caddy가 요청마다 /api/ide-auth/{port}로 주인 학생인지 묻는다.
 * Caddy 없이 이 값을 쓰면 IDE 포트가 누구에게나 열리므로 운영(Caddy 뒤)에서만 쓴다.</li>
 * </ul>
 */
public enum IdeAuthMode {
	PASSWORD, PROXY;

	/** 설정 값을 읽는다. 오타가 조용히 password나 proxy로 바뀌지 않도록 그 밖의 값은 시작을 멈춘다. */
	public static IdeAuthMode parse(String value) {
		String v = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
		return switch (v) {
			case "password" -> PASSWORD;
			case "proxy" -> PROXY;
			default -> throw new IllegalArgumentException(
					"lab.ide-auth(IDE_AUTH)는 password 또는 proxy여야 합니다: " + value);
		};
	}

	/** 이 기능 전에 만든 세션은 기록이 없다(null). 그때 컨테이너는 모두 비밀번호 방식이었다. */
	public static IdeAuthMode of(StudentSession s) {
		return s.getIdeAuthMode() == null ? PASSWORD : s.getIdeAuthMode();
	}
}
