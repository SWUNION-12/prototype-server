package com.swunion.lab.help;

import static org.assertj.core.api.Assertions.assertThat;

import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.session.SessionRepository;
import com.swunion.lab.session.StudentSession;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
class HelpRecoveryTest {

	@Autowired
	HelpRequestRepository helps;
	@Autowired
	CourseRepository courses;
	@Autowired
	SessionRepository sessions;

	@Test
	void processingRequestsFromBeforeStartupAreClosed() {
		Course c = courses.save(new Course("c", "3.12", "six==1.16.0"));
		StudentSession s = sessions.save(new StudentSession(c, "철수", "t-recover", "pw"));
		HelpRequest stuck = helps.save(new HelpRequest(s, "KeyError", "KeyError: a"));
		HelpRequest done = helps.save(new HelpRequest(s, "IndexError", "IndexError"));
		helps.updateResult(done.getId(), HelpStatus.EXPLAINED, "설명", false, null);
		Instant startedAt = Instant.now().plusSeconds(1);
		// 새 서버가 웹 요청을 받기 시작한 뒤(복구가 돌기 전) 들어온 요청은 살아 있는 실행이 처리한다.
		HelpRequest fresh = new HelpRequest(s, "TypeError", "TypeError");
		ReflectionTestUtils.setField(fresh, "createdAt", startedAt.plusSeconds(5));
		fresh = helps.save(fresh);

		int closed = new HelpRecovery(helps, startedAt).closeInterruptedHelps();

		assertThat(closed).isEqualTo(1);
		HelpRequest r = helps.findById(stuck.getId()).orElseThrow();
		assertThat(r.getStatus()).isEqualTo(HelpStatus.NO_ACTION);
		assertThat(r.getExplanation()).contains("서버가 다시 시작").contains("다시 요청");
		assertThat(r.isEscalated()).isFalse();
		assertThat(helps.findById(done.getId()).orElseThrow().getStatus()).isEqualTo(HelpStatus.EXPLAINED);
		assertThat(helps.findById(done.getId()).orElseThrow().getExplanation()).isEqualTo("설명");
		assertThat(helps.findById(fresh.getId()).orElseThrow().getStatus()).isEqualTo(HelpStatus.PROCESSING);
	}
}
