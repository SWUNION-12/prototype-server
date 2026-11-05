package com.swunion.lab.help;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.swunion.lab.agent.AgentTrigger;
import com.swunion.lab.config.LabProperties;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.session.SessionRepository;
import com.swunion.lab.session.SessionService;
import com.swunion.lab.session.SessionServices;
import com.swunion.lab.session.StudentSession;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import tools.jackson.databind.json.JsonMapper;

/** 학생 호출이 실제 DB에서 기존 강사 도움 이유를 어떻게 다루는지 본다. */
@DataJpaTest
class HelpEscalateTest {

	@Autowired
	SessionRepository sessions;
	@Autowired
	CourseRepository courses;
	@Autowired
	HelpRequestRepository helps;

	SessionService sessionService;
	HelpService help;
	StudentSession session;

	@BeforeEach
	void setUp() {
		Course c = new Course("수업", "3.12", "six==1.16.0");
		c.setStatus(CourseStatus.READY);
		c.setEntryCode("ESC001");
		c = courses.save(c);
		session = sessions.save(new StudentSession(c, "철수", "tok-escalate", "pw"));
		var props = new LabProperties("i", "p", "m", 15, "http://localhost:8080", "127.0.0.1", Path.of("."), "b:1",
				"t/course-", "swunion-test-s");
		sessionService = SessionServices.create(sessions, courses, mock(DockerGateway.class), props, (port, t) -> true,
				JsonMapper.builder().build());
		help = new HelpService(helps, sessionService, mock(AgentTrigger.class));
	}

	private StudentSession fresh() {
		return sessions.findById(session.getId()).orElseThrow();
	}

	@Test
	void studentCallKeepsEarlierReason() {
		sessionService.flagNeedsHelp(session.getId(), "재점검 실패: MISSING:six", "MISSING:six");

		help.escalate("tok-escalate");

		assertThat(fresh().isNeedsHelp()).isTrue();
		assertThat(fresh().getNeedsHelpReason()).isEqualTo("재점검 실패: MISSING:six / 학생 호출");
		assertThat(fresh().getLastEscalatedSignature()).isEqualTo("MISSING:six");

		// 여러 번 눌러도 같은 말을 덧붙이지 않는다.
		help.escalate("tok-escalate");
		assertThat(fresh().getNeedsHelpReason()).isEqualTo("재점검 실패: MISSING:six / 학생 호출");
	}

	@Test
	void studentCallWithoutEarlierReason() {
		help.escalate("tok-escalate");
		assertThat(fresh().isNeedsHelp()).isTrue();
		assertThat(fresh().getNeedsHelpReason()).isEqualTo("학생 호출");
	}
}
