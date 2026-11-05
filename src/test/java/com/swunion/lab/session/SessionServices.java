package com.swunion.lab.session;

import com.swunion.lab.config.LabProperties;
import com.swunion.lab.course.CourseRepository;
import com.swunion.lab.docker.DockerGateway;
import java.time.Clock;
import tools.jackson.databind.json.JsonMapper;

/** 단위 테스트가 SessionService의 협력 객체(스펙 생성기, 컨테이너 보장기)를 매번 조립하지 않게 한다. */
public final class SessionServices {

	private SessionServices() {
	}

	public static SessionService create(SessionRepository sessions, CourseRepository courses, DockerGateway docker,
			LabProperties props, HealthWaiter health, JsonMapper json) {
		ContainerSpecs specs = new ContainerSpecs(props);
		ContainerEnsurer ensurer = new ContainerEnsurer(sessions, docker, specs, Clock.systemUTC());
		return new SessionService(sessions, courses, docker, props, health, json, specs, ensurer);
	}
}
