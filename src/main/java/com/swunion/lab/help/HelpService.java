package com.swunion.lab.help;

import com.swunion.lab.agent.AgentTrigger;
import com.swunion.lab.session.SessionService;
import com.swunion.lab.session.StudentSession;
import org.springframework.stereotype.Service;

@Service
public class HelpService {

	static final int MAX_ERROR_TEXT = 4000;
	static final int MAX_EXTRACT_INPUT = 100_000;

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
		sessions.flagNeedsHelp(s.getId(), "학생 호출");
		helps.findFirstBySessionIdOrderByIdDesc(s.getId()).ifPresent(h -> helps.markEscalated(h.getId()));
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
