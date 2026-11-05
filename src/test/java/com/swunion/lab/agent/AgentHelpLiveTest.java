package com.swunion.lab.agent;

import static com.swunion.lab.agent.AgentFakes.OK;
import static org.assertj.core.api.Assertions.assertThat;

import com.swunion.lab.agent.AgentFakes.FakeChecker;
import com.swunion.lab.agent.AgentFakes.FakeGateway;
import com.swunion.lab.agent.llm.AnthropicLlmClient;
import com.swunion.lab.config.ApiKeyResolver;
import com.swunion.lab.config.LabProperties;
import com.swunion.lab.course.Course;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.course.CourseStatus;
import com.swunion.lab.session.SessionRepository;
import com.swunion.lab.session.SessionService;
import com.swunion.lab.session.SessionServices;
import com.swunion.lab.session.StudentSession;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 도움 요청 경로를 실제 LLM으로 한 번 돌린다(비용 때문에 1회). 환경은 정상(가짜 점검기)이라 Docker가 필요 없다.
 * 키는 ApiKeyResolver가 런타임에 읽는다(출력하지 않음).
 */
@Tag("llm")
@DataJpaTest(properties = "spring.config.import=optional:file:./build/no-such.env[.properties]")
class AgentHelpLiveTest {

	private static final Logger log = LoggerFactory.getLogger(AgentHelpLiveTest.class);

	@Autowired
	SessionRepository sessions;
	@Autowired
	CourseRepository courses;
	@Autowired
	AgentActionRepository actions;

	@Test
	void indexErrorIsExplainedWithoutCode() {
		assertThat(new ApiKeyResolver().resolve()).as("API 키(.env 또는 환경변수)").isPresent();
		JsonMapper json = JsonMapper.builder().build();
		Course c = new Course("파이썬 기초", "3.12", "six==1.16.0");
		c.setStatus(CourseStatus.READY);
		c.setEntryCode("HELP01");
		c = courses.save(c);
		StudentSession s = new StudentSession(c, "학생", "tok-help-live", "pw");
		s.setContainerId("c-live");
		s = sessions.save(s);

		LabProperties props = new LabProperties("i", "p", "claude-haiku-5-5", 15, "http://localhost:8080", "127.0.0.1",
				Path.of("."), "b:1", "t/course-", "swunion-test-s", "http://host.docker.internal:8080");
		FakeGateway docker = new FakeGateway();
		FakeChecker checker = new FakeChecker();
		SessionService sessionService = SessionServices.create(sessions, courses, docker, props, (port, t) -> true, json);
		AgentRunner runner = new AgentRunner(
				new AnthropicLlmClient(AnthropicLlmClient.newRestClient(RestClient.builder()), new ApiKeyResolver(), props),
				new AgentTools(checker, docker, sessionService, json), checker, sessionService, actions,
				new ArrayList<>()::add, json);

		String traceback = """
				Traceback (most recent call last):
				  File "/home/coder/project/main.py", line 4, in <module>
				    print(scores[3])
				          ~~~~~~^^^
				IndexError: list index out of range""";
		AgentAction a = runner.run(new AgentContext(s, Trigger.HELP, OK, "IndexError", traceback, 1L));

		log.info("HELP 실행: outcome={} classification={} summary={}\n  explanation={}\n  toolLog={}\n  rejected={}"
				+ "\n  restoreExecs={}", a.getOutcome(), a.getClassification(), a.getSummary(), a.getExplanation(),
				a.getToolLogJson(), a.getRejectedJson(), docker.execs.size());
		assertThat(a.getOutcome()).isEqualTo(Outcome.EXPLAINED);
		assertThat(a.getExplanation()).isNotBlank().doesNotContain("```");
	}
}
