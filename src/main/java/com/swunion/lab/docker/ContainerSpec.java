package com.swunion.lab.docker;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @param cmd 이미지 CMD 대신 줄 실행 인자. null이면 이미지의 CMD를 그대로 쓴다.
 */
public record ContainerSpec(String name, String image, Map<String, String> env, String volumeName,
		String volumeMountPath, String bindAddress, int containerPort, Map<String, String> labels, List<String> cmd) {

	public ContainerSpec {
		cmd = cmd == null ? null : List.copyOf(cmd);
	}

	/** 이미지의 CMD를 그대로 쓰는 명세. */
	public ContainerSpec(String name, String image, Map<String, String> env, String volumeName,
			String volumeMountPath, String bindAddress, int containerPort, Map<String, String> labels) {
		this(name, image, env, volumeName, volumeMountPath, bindAddress, containerPort, labels, null);
	}

	// env에 PASSWORD 같은 비밀이 들어가므로 로그에 값이 남지 않게 가린다. 실행 인자에는 비밀이 없다.
	@Override
	public String toString() {
		String keys = env == null ? "{}" : env.keySet().stream().map(k -> k + "=***")
				.collect(Collectors.joining(", ", "{", "}"));
		return "ContainerSpec[name=" + name + ", image=" + image + ", env=" + keys + ", volumeName=" + volumeName
				+ ", volumeMountPath=" + volumeMountPath + ", bindAddress=" + bindAddress + ", containerPort="
				+ containerPort + ", labels=" + labels + ", cmd=" + cmd + "]";
	}
}
