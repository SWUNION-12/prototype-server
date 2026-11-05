package com.swunion.lab.help;

import static org.assertj.core.api.Assertions.assertThat;

import com.swunion.lab.agent.Classification;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseProvisioner;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.docker.DockerGateway;
import com.swunion.lab.session.HealthWaiter;
import com.swunion.lab.session.SessionRepository;
import com.swunion.lab.session.StudentSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
		"spring.config.import=optional:file:./build/no-such.env[.properties]",
		"spring.datasource.url=jdbc:h2:mem:helprepo"
})
class HelpRequestRepositoryTest {

	@Autowired
	HelpRequestRepository helps;
	@Autowired
	CourseRepository courses;
	@Autowired
	SessionRepository sessions;
	@MockitoBean
	DockerGateway docker;
	@MockitoBean
	HealthWaiter health;
	@MockitoBean
	CourseProvisioner provisioner;

	@Test
	void escalatedSurvivesResultUpdate() {
		Course c = courses.save(new Course("c", "3.12", "six==1.16.0"));
		StudentSession s = sessions.save(new StudentSession(c, "철수", "t-repo", "pw"));
		HelpRequest h = helps.save(new HelpRequest(s, "KeyError", "KeyError: a"));

		helps.markEscalated(h.getId());
		helps.updateResult(h.getId(), HelpStatus.EXPLAINED, "설명", false, null);

		HelpRequest r = helps.findById(h.getId()).orElseThrow();
		assertThat(r.getStatus()).isEqualTo(HelpStatus.EXPLAINED);
		assertThat(r.getExplanation()).isEqualTo("설명");
		assertThat(r.isEscalated()).isTrue();
		assertThat(helps.findBySessionCourseIdAndErrorTypeIsNotNull(c.getId())).hasSize(1);

		// 호출하지 않은 요청은 에이전트가 올린 값만 따른다.
		HelpRequest h2 = helps.save(new HelpRequest(s, null, null));
		helps.updateResult(h2.getId(), HelpStatus.RESOLVED, null, false, null);
		assertThat(helps.findById(h2.getId()).orElseThrow().isEscalated()).isFalse();
	}

	@Test
	void updateResultStoresClassification() {
		Course c = courses.save(new Course("c", "3.12", "six==1.16.0"));
		StudentSession s = sessions.save(new StudentSession(c, "영희", "t-repo-cls", "pw"));
		HelpRequest h = helps.save(new HelpRequest(s, "KeyError", "KeyError: a"));
		assertThat(helps.findById(h.getId()).orElseThrow().getClassification()).isNull();

		helps.updateResult(h.getId(), HelpStatus.EXPLAINED, "설명", false, Classification.ENV);

		assertThat(helps.findById(h.getId()).orElseThrow().getClassification()).isEqualTo(Classification.ENV);
	}
}
