package com.swunion.lab.docker;

import java.util.Map;

public record ContainerSpec(String name, String image, Map<String, String> env, String volumeName,
		String volumeMountPath, String bindAddress, int containerPort, Map<String, String> labels) {
}
