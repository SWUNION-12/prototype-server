package com.swunion.lab.docker;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/** 서버가 Docker를 다루는 유일한 창구. 다른 코드는 docker-java를 직접 쓰지 않는다. */
public interface DockerGateway {

	boolean imageExists(String image);

	/** @return 이미지 id. 실패 시 로그 끝 4,000자를 담은 DockerOperationException. */
	String buildImage(Path contextDir, String tag, Consumer<String> log);

	/** 임시 컨테이너에서 ENTRYPOINT를 무시하고 명령을 실행한 뒤 컨테이너를 지운다. */
	ExecResult runOnce(String image, List<String> entrypointAndArgs, Duration timeout);

	String createAndStart(ContainerSpec spec);

	int hostPort(String containerId, int containerPort);

	/** 없는 컨테이너는 false. */
	boolean isRunning(String containerId);

	/** 항상 user coder로 실행한다. */
	ExecResult exec(String containerId, List<String> cmd, Duration timeout);

	ExecResult exec(String containerId, List<String> cmd, String workDir, Duration timeout);

	void restart(String containerId);

	void stop(String containerId);

	/** 멈춘 컨테이너도 true. 없으면 false. 404가 아닌 오류는 DockerOperationException. */
	boolean containerExists(String containerId);

	/** 없으면 false. 404가 아닌 오류는 DockerOperationException. */
	boolean volumeExists(String volumeName);

	/** 컨테이너만 지운다. 볼륨은 남는다. 이미 없으면 조용히 끝나고, 그 밖의 오류는 DockerOperationException. */
	void remove(String containerId);

	/** 이미 없으면 조용히 끝난다. 컨테이너가 붙어 있는 등 그 밖의 오류는 DockerOperationException. */
	void removeVolume(String volumeName);

	/** 강제로 지운다. 이미 없으면 조용히 끝나고, 그 밖의 오류는 DockerOperationException. */
	void removeImage(String image);

	/** 컨테이너 경로를 tar 스트림으로 돌려준다. 호출자가 닫는다. */
	InputStream copyArchive(String containerId, String path);
}
