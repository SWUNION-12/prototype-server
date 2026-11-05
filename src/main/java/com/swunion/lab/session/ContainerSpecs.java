package com.swunion.lab.session;

import com.swunion.lab.config.LabProperties;
import com.swunion.lab.course.Course;
import com.swunion.lab.docker.ContainerSpec;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 학생 컨테이너를 만드는 명세의 유일한 출처. 입장과 컨테이너 되살리기가 같은 이름·볼륨·라벨·환경으로 만들어야
 * 점검, 정리, 학생 화면이 새 컨테이너를 원래 컨테이너와 같은 학생의 것으로 다룬다.
 */
@Component
public class ContainerSpecs {

	static final int IDE_PORT = 8080;
	static final String MOUNT_PATH = "/home/coder/project";

	private final LabProperties props;

	public ContainerSpecs(LabProperties props) {
		this.props = props;
	}

	/**
	 * 이름과 볼륨 모두 세션의 volumeName이다. 컨테이너를 지우고 다시 만들어도 같은 볼륨이 붙어 학생 코드가 이어진다.
	 * IDE 비밀번호도 세션에 저장된 값을 그대로 쓴다.
	 */
	public ContainerSpec forSession(StudentSession s, Course c) {
		return new ContainerSpec(s.getVolumeName(), c.getImageName(), Map.of("PASSWORD", s.getIdePassword()),
				s.getVolumeName(), MOUNT_PATH, props.ideBindAddress(), IDE_PORT,
				Map.of("swunion.session", String.valueOf(s.getId()), "swunion.course", String.valueOf(c.getId())));
	}
}
