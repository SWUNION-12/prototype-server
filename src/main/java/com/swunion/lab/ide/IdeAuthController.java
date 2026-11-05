package com.swunion.lab.ide;

import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.session.SessionNotFoundException;
import com.swunion.lab.session.SessionService;
import com.swunion.lab.session.StudentCookie;
import com.swunion.lab.session.StudentSession;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영(Caddy 뒤)에서 /ide/{port}/ 로 가는 요청마다 Caddy forward_auth가 부르는 확인 API. 학생 쿠키의 세션이 그 IDE 포트의
 * 지금 주인이고, 그 컨테이너가 돌고 있고, 수업이 열려 있을 때만 204를 준다. 그 밖(쿠키 없음, 무효 쿠키, 남의 포트,
 * 멈춘 컨테이너, 종료된 수업, 이상한 포트 값)은 모두 401이다. 어느 이유로 거절했는지는 알려 주지 않는다. 401 본문은
 * Caddy가 브라우저에 그대로 돌려주므로 짧은 안내 문구다. GET이라 CSRF와 무관하다.
 * <p>
 * 한 포트의 주인은 SessionRepository가 하나로 지킨다(포트를 기록할 때 같은 포트의 옛 기록을 지우고, 멈추면 포트를
 * 지운다). 컨테이너 실행 여부는 점검 주기만큼 늦게 바뀌므로 그 확인은 덧댄 방어다.
 */
@RestController
public class IdeAuthController {

	// 부호와 앞자리 0이 없는 1~5자리 숫자만 포트로 본다. 같은 포트를 여러 표기로 묻지 못하게 한다.
	private static final Pattern PORT = Pattern.compile("^[1-9][0-9]{0,4}$");
	static final String DENIED = "이 IDE를 열 수 없습니다. 코딩책상에 입장한 브라우저에서 열어 주세요.";
	private static final MediaType TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);

	private final SessionService sessions;

	public IdeAuthController(SessionService sessions) {
		this.sessions = sessions;
	}

	@GetMapping("/api/ide-auth/{port}")
	ResponseEntity<String> check(@PathVariable String port,
			@CookieValue(name = StudentCookie.NAME, required = false) String token) {
		if (!PORT.matcher(port).matches()) {
			return denied();
		}
		StudentSession s;
		try {
			s = sessions.byToken(token);
		}
		catch (SessionNotFoundException e) {
			return denied();
		}
		Integer own = s.getHostPort();
		if (own == null || own != Integer.parseInt(port) || !s.isContainerRunning() || s.getCourse() == null
				|| s.getCourse().getStatus() != CourseStatus.READY) {
			return denied();
		}
		return ResponseEntity.noContent().build();
	}

	// WWW-Authenticate를 붙이지 않는다. 붙이면 브라우저가 IDE 화면 대신 로그인 창을 띄운다.
	private static ResponseEntity<String> denied() {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(TEXT).body(DENIED);
	}
}
