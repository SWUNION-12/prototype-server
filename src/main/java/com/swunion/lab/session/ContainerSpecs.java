package com.swunion.lab.session;

import com.swunion.lab.config.LabProperties;
import com.swunion.lab.course.Course;
import com.swunion.lab.docker.ContainerSpec;
import java.util.LinkedHashMap;
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
		return new ContainerSpec(s.getVolumeName(), c.getImageName(), env(s), s.getVolumeName(), MOUNT_PATH,
				props.ideBindAddress(), IDE_PORT,
				Map.of("swunion.session", String.valueOf(s.getId()), "swunion.course", String.valueOf(c.getId())));
	}

	/**
	 * 컨테이너 환경변수. IDE 안 확장이 SWUNION_SERVER_URL의 서버에 SWUNION_IDE_KEY로 자기를 알린다.
	 * 재생성도 이 메서드를 거치므로 세션의 같은 키가 다시 들어간다. 키 없는 예전 세션은 키 변수만 뺀다.
	 */
	private Map<String, String> env(StudentSession s) {
		Map<String, String> env = new LinkedHashMap<>();
		env.put("PASSWORD", s.getIdePassword());
		if (s.getIdeKey() != null) {
			env.put("SWUNION_IDE_KEY", s.getIdeKey());
		}
		env.put("SWUNION_SERVER_URL", props.serverUrlForContainers());
		return env;
	}
}
