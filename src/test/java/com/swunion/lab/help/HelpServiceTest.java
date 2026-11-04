package com.swunion.lab.help;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swunion.lab.agent.AgentAction;
import com.swunion.lab.agent.AgentFinishedEvent;
import com.swunion.lab.agent.AgentTrigger;
import com.swunion.lab.agent.Outcome;
import com.swunion.lab.session.SessionNotFoundException;
import com.swunion.lab.session.SessionService;
import com.swunion.lab.session.StudentSession;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HelpServiceTest {

	HelpRequestRepository helps = mock(HelpRequestRepository.class);
	SessionService sessions = mock(SessionService.class);
	AgentTrigger trigger = mock(AgentTrigger.class);
	StudentSession session = mock(StudentSession.class);
	HelpService service = new HelpService(helps, sessions, trigger);

	HelpServiceTest() {
		when(session.getId()).thenReturn(7L);
		when(sessions.byToken("tok")).thenReturn(session);
		// IDENTITY가 채워 주는 id를 흉내 낸다.
		when(helps.save(any())).thenAnswer(i -> {
			HelpRequest h = i.getArgument(0);
			org.springframework.test.util.ReflectionTestUtils.setField(h, "id", 1L);
			return h;
		});
	}

	@Test
	void busyReturnsConflictWithoutSaving() {
		when(trigger.isRunning(7L)).thenReturn(true);
		assertThatThrownBy(() -> service.request("tok", "KeyError")).isInstanceOf(HelpBusyException.class);
		verify(helps, never()).save(any());
		verify(trigger, never()).onHelp(any(), anyLong(), any(), any());
	}

	@Test
	void raceOnHelpDeletesSavedRow() {
		org.mockito.Mockito.doAnswer(i -> {
			HelpRequest h = i.getArgument(0);
			org.springframework.test.util.ReflectionTestUtils.setField(h, "id", 5L);
			return h;
		}).when(helps).save(any());
		when(trigger.onHelp(eq(session), eq(5L), any(), any())).thenReturn(false);
		assertThatThrownBy(() -> service.request("tok", "x")).isInstanceOf(HelpBusyException.class);
		verify(helps).deleteById(5L);
	}

	@Test
	void truncatesErrorText() {
		when(trigger.onHelp(any(), anyLong(), any(), any())).thenReturn(true);
		HelpRequest h = service.request("tok", "a".repeat(5000));
		assertThat(h.getErrorText()).hasSize(4000);
	}

	@Test
	void errorTypeComesFromUntruncatedText() {
		when(trigger.onHelp(any(), anyLong(), any(), any())).thenReturn(true);
		String text = "KeyError: 'a'\n" + "x".repeat(4500) + "\nValueError: late";
		HelpRequest h = service.request("tok", text);
		assertThat(h.getErrorText()).hasSize(4000);
		assertThat(h.getErrorType()).isEqualTo("ValueError");
		String huge = "KeyError: early\n" + "y".repeat(150_000) + "\nTypeError: tail";
		assertThat(service.request("tok", huge).getErrorType()).isEqualTo("TypeError");
	}

	@Test
	void truncationKeepsSurrogatePairsWhole() {
		String text = "a".repeat(3999) + "😀" + "b";
		String out = HelpService.normalize(text);
		assertThat(out).hasSize(3999);
		assertThat(Character.isHighSurrogate(out.charAt(out.length() - 1))).isFalse();
	}

	@Test
	void blankTextStoredAsNullAndTypeExtracted() {
		when(trigger.onHelp(any(), anyLong(), any(), any())).thenReturn(true);
		HelpRequest blank = service.request("tok", "  \n");
		assertThat(blank.getErrorText()).isNull();
		assertThat(blank.getErrorType()).isNull();
		HelpRequest typed = service.request("tok", "ValueError: bad");
		assertThat(typed.getErrorType()).isEqualTo("ValueError");
		verify(trigger).onHelp(eq(session), anyLong(), eq("ValueError"), eq("ValueError: bad"));
	}

	@Test
	void unknownTokenPropagates() {
		when(sessions.byToken("bad")).thenThrow(new SessionNotFoundException());
		assertThatThrownBy(() -> service.request("bad", "x")).isInstanceOf(SessionNotFoundException.class);
	}

	@Test
	void escalateFlagsAndMarksLatest() {
		HelpRequest h = new HelpRequest(session, null, null);
		org.springframework.test.util.ReflectionTestUtils.setField(h, "id", 3L);
		when(helps.findFirstBySessionIdOrderByIdDesc(7L)).thenReturn(Optional.of(h));
		service.escalate("tok");
		verify(sessions).flagNeedsHelp(7L, "학생 호출");
		verify(helps).markEscalated(3L);
	}

	@Test
	void escalateWithoutHelpOnlyFlags() {
		when(helps.findFirstBySessionIdOrderByIdDesc(7L)).thenReturn(Optional.empty());
		service.escalate("tok");
		verify(sessions).flagNeedsHelp(7L, "학생 호출");
		verify(helps, never()).markEscalated(anyLong());
	}

	@Test
	void listenerMapsEveryOutcome() {
		HelpResultListener l = new HelpResultListener(helps);
		for (Outcome o : Outcome.values()) {
			AgentAction a = mock(AgentAction.class);
			when(a.getOutcome()).thenReturn(o);
			when(a.getExplanation()).thenReturn("설명");
			l.onFinished(new AgentFinishedEvent(a, 9L));
			verify(helps).updateResult(9L, HelpStatus.valueOf(o.name()), "설명", o == Outcome.ESCALATED);
			org.mockito.Mockito.clearInvocations(helps);
		}
	}

	@Test
	void listenerHandlesUnsavedActionAndNullHelpId() {
		HelpResultListener l = new HelpResultListener(helps);
		AgentAction a = mock(AgentAction.class); // id null, explanation null
		when(a.getOutcome()).thenReturn(Outcome.ESCALATED);
		l.onFinished(new AgentFinishedEvent(a, 4L));
		ArgumentCaptor<String> expl = ArgumentCaptor.forClass(String.class);
		verify(helps).updateResult(eq(4L), eq(HelpStatus.ESCALATED), expl.capture(), eq(true));
		assertThat(expl.getValue()).isNull();
		org.mockito.Mockito.clearInvocations(helps);
		l.onFinished(new AgentFinishedEvent(a, null));
		verify(helps, never()).updateResult(anyLong(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
	}

	@Test
	void listenerSurvivesMissingRow() {
		HelpResultListener l = new HelpResultListener(helps);
		AgentAction a = mock(AgentAction.class);
		when(a.getOutcome()).thenReturn(Outcome.RESOLVED);
		when(helps.updateResult(anyLong(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(0);
		l.onFinished(new AgentFinishedEvent(a, 1L));
	}
}
