package com.swunion.lab.help;

import com.swunion.lab.agent.AgentTrigger;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.session.SessionService;
import com.swunion.lab.session.StudentSession;
import org.springframework.stereotype.Service;

@Service
public class HelpService {

	static final int MAX_ERROR_TEXT = 4000;
	static final int MAX_EXTRACT_INPUT = 100_000;
	static final String STUDENT_CALL = "학생 호출";

	private final HelpRequestRepository helps;
	private final SessionService sessions;
	private final AgentTrigger trigger;

	public HelpService(HelpRequestRepository helps, SessionService sessions, AgentTrigger trigger) {
		this.helps = helps;
		this.sessions = sessions;
		this.trigger = trigger;
	}

	public HelpRequest request(String token, String errorText) {
		StudentSession s = sessions.byToken(token);
		requireOpenCourse(s);
		if (trigger.isRunning(s.getId())) {
			throw new HelpBusyException();
		}
		String text = normalize(errorText);
		// 마지막 줄이 최종 오류인 traceback은 4,000자 뒤에 있을 수 있어, 자르기 전 원문의 꼬리에서 뽑는다.
		String type = text == null ? null : ErrorTypeExtractor.extract(tail(errorText)).orElse(null);
		HelpRequest help = helps.save(new HelpRequest(s, type, text));
		// isRunning 확인과 onHelp 사이에 다른 실행이 끼면 false가 온다. 아무도 결과를 채우지 않으므로 지운다.
		if (!trigger.onHelp(s, help.getId(), type, text)) {
			helps.deleteById(help.getId());
			throw new HelpBusyException();
		}
		return help;
	}

	public void escalate(String token) {
		StudentSession s = sessions.byToken(token);
		requireOpenCourse(s);
		// 에이전트가 먼저 남긴 이유(예: "재점검 실패: …")를 지우면 강사가 원인을 못 본다. 뒤에 덧붙인다.
		sessions.addNeedsHelpReason(s.getId(), STUDENT_CALL);
		helps.findFirstBySessionIdOrderByIdDesc(s.getId()).ifPresent(h -> helps.markEscalated(h.getId()));
	}

	/**
	 * 종료된 수업의 도움 요청이 에이전트를 깨우면 사전 점검이 IDE_DOWN을 보고 restart_ide가 멈춘 컨테이너를
	 * 되살린다. 수업이 열려 있을 때(READY)만 받는다. 세션은 READY 수업에서만 생기므로 그 밖은 종료뿐이다.
	 */
	private static void requireOpenCourse(StudentSession s) {
		if (s.getCourse() == null || s.getCourse().getStatus() != CourseStatus.READY) {
			throw new CourseEndedException();
		}
	}

	public HelpRequest latest(long sessionId) {
		return helps.findFirstBySessionIdOrderByIdDesc(sessionId).orElse(null);
	}

	static String tail(String text) {
		return text.length() <= MAX_EXTRACT_INPUT ? text : text.substring(text.length() - MAX_EXTRACT_INPUT);
	}

	static String normalize(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		if (text.length() <= MAX_ERROR_TEXT) {
			return text;
		}
		int end = MAX_ERROR_TEXT;
		// 서로게이트 쌍의 앞 절반에서 자르면 깨진 문자가 DB에 들어간다.
		if (Character.isHighSurrogate(text.charAt(end - 1))) {
			end--;
		}
		return text.substring(0, end);
	}
}
