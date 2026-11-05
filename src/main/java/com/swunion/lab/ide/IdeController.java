package com.swunion.lab.ide;

import com.swunion.lab.help.CourseEndedException;
import com.swunion.lab.help.HelpBusyException;
import com.swunion.lab.help.HelpRequest;
import com.swunion.lab.help.HelpService;
import com.swunion.lab.session.SessionNotFoundException;
import com.swunion.lab.session.SessionService;
import com.swunion.lab.session.StudentController.HelpBody;
import com.swunion.lab.session.StudentSession;
import com.swunion.lab.session.StudentStatusView;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 학생 컨테이너 안의 확장(IDE 도움 패널)이 부르는 API. 요청마다 헤더 X-Session-Key의 컨테이너 키로 세션을 찾고,
 * 그 세션 자신의 상태 조회, 도움 요청, 강사 호출만 한다. 강사 Basic 인증이나 학생 토큰과는 별개이고 SecurityConfig는
 * 이 경로를 열어 둔다(인증은 키가 한다). 키는 응답과 로그 어디에도 남기지 않는다.
 */
@RestController
@RequestMapping("/api/ide")
public class IdeController {

	static final String KEY_HEADER = "X-Session-Key";

	private final SessionService sessions;
	private final HelpService help;

	public IdeController(SessionService sessions, HelpService help) {
		this.sessions = sessions;
		this.help = help;
	}

	// 학생 화면의 같은 응답에서 IDE 주소·비밀번호를 뺀 것이다. 토큰과 키도 싣지 않는다.
	@GetMapping("/status")
	Map<String, Object> status(@RequestHeader(name = KEY_HEADER, required = false) String key) {
		StudentSession s = sessions.byIdeKey(key);
		return StudentStatusView.of(s, sessions.issues(s), sessions.issueTexts(s), help.latest(s.getId()));
	}

	@PostMapping("/help")
	ResponseEntity<Map<String, Object>> help(@RequestHeader(name = KEY_HEADER, required = false) String key,
			@RequestBody(required = false) HelpBody body) {
		StudentSession s = sessions.byIdeKey(key);
		HelpRequest h = help.request(s, body == null ? null : body.errorText());
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("helpId", h.getId()));
	}

	@PostMapping("/escalate")
	ResponseEntity<Void> escalate(@RequestHeader(name = KEY_HEADER, required = false) String key) {
		help.escalate(sessions.byIdeKey(key));
		return ResponseEntity.noContent().build();
	}

	// 키가 없든 틀리든 같은 응답이다. 어느 쪽인지, 키 없는 세션이 있는지 알려 주지 않는다.
	@ExceptionHandler(SessionNotFoundException.class)
	ResponseEntity<Map<String, String>> unauthorized() {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "인증 정보가 없습니다"));
	}

	@ExceptionHandler(HelpBusyException.class)
	ResponseEntity<Map<String, String>> busy(HelpBusyException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
	}

	@ExceptionHandler(CourseEndedException.class)
	ResponseEntity<Map<String, String>> courseEnded(CourseEndedException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
	}
}
