package com.swunion.lab.docker;

import java.util.Map;
import java.util.stream.Collectors;

public record ContainerSpec(String name, String image, Map<String, String> env, String volumeName,
		String volumeMountPath, String bindAddress, int containerPort, Map<String, String> labels) {

	// env에 PASSWORD 같은 비밀이 들어가므로 로그에 값이 남지 않게 가린다.
	@Override
	public String toString() {
		String keys = env == null ? "{}" : env.keySet().stream().map(k -> k + "=***")
				.collect(Collectors.joining(", ", "{", "}"));
		return "ContainerSpec[name=" + name + ", image=" + image + ", env=" + keys + ", volumeName=" + volumeName
				+ ", volumeMountPath=" + volumeMountPath + ", bindAddress=" + bindAddress + ", containerPort="
				+ containerPort + ", labels=" + labels + "]";
	}
}
