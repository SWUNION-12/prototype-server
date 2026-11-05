package com.swunion.lab.help;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swunion.lab.agent.AgentAction;
import com.swunion.lab.agent.AgentFinishedEvent;
import com.swunion.lab.agent.Classification;
import com.swunion.lab.agent.Outcome;
import org.junit.jupiter.api.Test;

class HelpResultListenerTest {

	HelpRequestRepository helps = mock(HelpRequestRepository.class);
	HelpResultListener listener = new HelpResultListener(helps);

	@Test
	void copiesClassification() {
		AgentAction a = mock(AgentAction.class);
		when(a.getOutcome()).thenReturn(Outcome.RESOLVED);
		when(a.getExplanation()).thenReturn("패키지를 설치했어요");
		when(a.getClassification()).thenReturn(Classification.ENV);

		listener.onFinished(new AgentFinishedEvent(a, 7L));

		verify(helps).updateResult(7L, HelpStatus.RESOLVED, "패키지를 설치했어요", false, Classification.ENV);
	}

	@Test
	void nullActionGivesNoActionWithoutClassification() {
		listener.onFinished(new AgentFinishedEvent(null, 8L));

		verify(helps).updateResult(8L, HelpStatus.NO_ACTION, null, false, null);
	}

	@Test
	void unclassifiedActionPassesNull() {
		AgentAction a = mock(AgentAction.class);
		when(a.getOutcome()).thenReturn(Outcome.ESCALATED);

		listener.onFinished(new AgentFinishedEvent(a, 9L));

		verify(helps).updateResult(eq(9L), eq(HelpStatus.ESCALATED),
				isNull(), eq(true), isNull());
	}
}
