package com.swunion.lab.session;

import com.swunion.lab.config.LabProperties;
import com.swunion.lab.course.Course;
import com.swunion.lab.docker.ContainerSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 학생 컨테이너를 만드는 명세의 유일한 출처. 입장과 컨테이너 되살리기가 같은 이름·볼륨·라벨·환경으로 만들어야
 * 점검, 정리, 학생 화면이 새 컨테이너를 원래 컨테이너와 같은 학생의 것으로 다룬다.
 */
@Component
public class ContainerSpecs {

	static final int IDE_PORT = 8080;
	static final String MOUNT_PATH = "/home/coder/project";
	private static final Pattern LOOPBACK = Pattern.compile("^(127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|::1)$");

	/**
	 * proxy 방식의 code-server 실행 인자. 기본 이미지(docker/lab-base/Dockerfile)의 CMD에서 --auth password만
	 * --auth none으로 바꾼 것이다. 이미지는 그대로 두고 컨테이너를 만들 때만 바꾼다. Dockerfile의 CMD가 바뀌면
	 * ContainerSpecsTest가 깨져 여기도 맞추게 한다.
	 */
	static final List<String> PROXY_CMD = List.of("--bind-addr", "0.0.0.0:8080", "--auth", "none",
			"--disable-telemetry", "--disable-update-check", MOUNT_PATH);

	private final LabProperties props;
	private final IdeAuthMode ideAuth;

	@Autowired
	public ContainerSpecs(LabProperties props, @Value("${lab.ide-auth:password}") String ideAuth) {
		this(props, IdeAuthMode.parse(ideAuth));
	}

	/** 비밀번호 방식(기본). */
	public ContainerSpecs(LabProperties props) {
		this(props, IdeAuthMode.PASSWORD);
	}

	/**
	 * @throws IllegalStateException proxy인데 IDE 포트를 이 컴퓨터 밖에도 연다. 비밀번호 없는 IDE(학생 컨테이너 셸)가
	 *                               Caddy를 거치지 않고 열리는 조합이라 쓸 곳이 없으므로 시작을 멈춘다. 주소 값은 싣지 않는다.
	 */
	public ContainerSpecs(LabProperties props, IdeAuthMode ideAuth) {
		if (ideAuth == IdeAuthMode.PROXY
				&& (props.ideBindAddress() == null || !LOOPBACK.matcher(props.ideBindAddress()).matches())) {
			throw new IllegalStateException("IDE_AUTH=proxy이면 IDE_BIND_ADDRESS는 127.0.0.1이어야 합니다. 그렇지 않으면 "
					+ "Caddy를 거치지 않은 접속이 비밀번호 없이 학생 IDE에 들어갑니다.");
		}
		this.props = props;
		this.ideAuth = ideAuth;
	}

	/** 지금 만드는 컨테이너의 IDE 확인 방식. 컨테이너를 기록할 때 세션에 함께 남긴다. */
	public IdeAuthMode ideAuthMode() {
		return ideAuth;
	}

	/**
	 * 이름과 볼륨 모두 세션의 volumeName이다. 컨테이너를 지우고 다시 만들어도 같은 볼륨이 붙어 학생 코드가 이어진다.
	 * IDE 비밀번호도 세션에 저장된 값을 그대로 쓴다. 실행 인자는 세션이 아니라 지금 설정을 따른다. 이것만으로는 설정을
	 * password로 되돌렸을 때 이미 있는 proxy 컨테이너를 막지 못한다(다시 켜면 처음 인자 그대로다). 그 경우는
	 * ContainerEnsurer가 다시 켜는 대신 같은 볼륨으로 새로 만든다.
	 */
	public ContainerSpec forSession(StudentSession s, Course c) {
		return new ContainerSpec(s.getVolumeName(), c.getImageName(), env(s), s.getVolumeName(), MOUNT_PATH,
				props.ideBindAddress(), IDE_PORT,
				Map.of("swunion.session", String.valueOf(s.getId()), "swunion.course", String.valueOf(c.getId())),
				ideAuth == IdeAuthMode.PROXY ? PROXY_CMD : null);
	}

	/**
	 * 컨테이너 환경변수. IDE 안 확장이 SWUNION_SERVER_URL의 서버에 SWUNION_IDE_KEY로 자기를 알린다.
	 * 재생성도 이 메서드를 거치므로 세션의 같은 키가 다시 들어간다. 키 없는 예전 세션은 키 변수만 뺀다.
	 */
	private Map<String, String> env(StudentSession s) {
		Map<String, String> env = new LinkedHashMap<>();
		// proxy 방식에서는 code-server가 쓰지 않지만, 같은 세션을 나중에 비밀번호 방식으로 다시 만들 때와 값을 맞춰 둔다.
		env.put("PASSWORD", s.getIdePassword());
		if (s.getIdeKey() != null) {
			env.put("SWUNION_IDE_KEY", s.getIdeKey());
		}
		env.put("SWUNION_SERVER_URL", props.serverUrlForContainers());
		// 프록시 뒤에서는 모든 학생 IDE가 한 주소를 쓴다. 쿠키 이름을 세션마다 다르게 해 한 브라우저에서 로그인이 덮이지 않게 한다.
		env.put("CODE_SERVER_COOKIE_SUFFIX", s.getVolumeName());
		return env;
	}
}
