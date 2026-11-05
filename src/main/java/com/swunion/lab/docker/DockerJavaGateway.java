package com.swunion.lab.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.BuildResponseItem;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Volume;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class DockerJavaGateway implements DockerGateway {

	private static final int LOG_TAIL_CHARS = 4000;
	private static final String STUDENT_USER = "coder";

	private final DockerClient docker;

	public DockerJavaGateway(DockerClient docker) {
		this.docker = docker;
	}

	@Override
	public boolean imageExists(String image) {
		try {
			docker.inspectImageCmd(image).exec();
			return true;
		}
		catch (NotFoundException e) {
			return false;
		}
	}

	@Override
	public String buildImage(Path contextDir, String tag, Consumer<String> log) {
		StringBuilder all = new StringBuilder();
		String[] imageId = new String[1];
		String[] error = new String[1];
		var callback = new ResultCallback.Adapter<BuildResponseItem>() {
			@Override
			public void onNext(BuildResponseItem item) {
				if (item.getStream() != null) {
					all.append(item.getStream());
					log.accept(item.getStream());
				}
				if (item.getErrorDetail() != null || item.getError() != null) {
					String msg = item.getErrorDetail() != null && item.getErrorDetail().getMessage() != null
							? item.getErrorDetail().getMessage() : item.getError();
					error[0] = msg;
					all.append(msg).append('\n');
					log.accept(msg);
				}
				if (item.isBuildSuccessIndicated() || item.getImageId() != null) {
					imageId[0] = item.getImageId() != null ? item.getImageId() : imageId[0];
				}
			}
		};
		try {
			docker.buildImageCmd().withBaseDirectory(contextDir.toFile())
					.withDockerfile(contextDir.resolve("Dockerfile").toFile()).withTags(Set.of(tag))
					// withRemove는 성공한 단계의 중간 컨테이너만 지운다. 실패한 RUN 단계의 컨테이너는 forcerm이어야 지워진다.
					.withNoCache(false).withRemove(true).withForcerm(true).exec(callback).awaitCompletion();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new DockerOperationException("이미지 빌드가 중단되었습니다.", e);
		}
		catch (RuntimeException e) {
			throw new DockerOperationException("이미지 빌드 실패: " + e.getMessage() + "\n" + tail(all), e);
		}
		if (error[0] != null || imageId[0] == null) {
			throw new DockerOperationException("이미지 빌드 실패:\n" + tail(all));
		}
		return imageId[0];
	}

	/**
	 * 호출자는 이 메시지를 응답과 로그에 그대로 남긴다. 그래서 대상 이름과 HTTP 상태만 담고,
	 * 데몬이 돌려준 본문은 cause에만 둔다.
	 */
	private static DockerOperationException failure(String what, String target, RuntimeException cause) {
		String status = cause instanceof DockerException de ? " (HTTP " + de.getHttpStatus() + ")" : "";
		return new DockerOperationException(what + ": " + target + status, cause);
	}

	private static String tail(CharSequence s) {
		return s.length() <= LOG_TAIL_CHARS ? s.toString() : s.subSequence(s.length() - LOG_TAIL_CHARS, s.length()).toString();
	}

	@Override
	public ExecResult runOnce(String image, List<String> entrypointAndArgs, Duration timeout) {
		if (entrypointAndArgs.isEmpty()) {
			throw new IllegalArgumentException("명령이 비어 있습니다.");
		}
		String id = null;
		try {
			// 이미지 ENTRYPOINT(code-server 등)를 덮어써서 명령이 그대로 실행되게 한다.
			id = docker.createContainerCmd(image).withEntrypoint(entrypointAndArgs.get(0))
					.withCmd(entrypointAndArgs.subList(1, entrypointAndArgs.size())).exec().getId();
			docker.startContainerCmd(id).exec();
			Integer code = docker.waitContainerCmd(id).start().awaitStatusCode(timeout.toMillis(), TimeUnit.MILLISECONDS);
			var out = new ByteArrayOutputStream();
			var err = new ByteArrayOutputStream();
			var logs = new FrameCollector(out, err);
			docker.logContainerCmd(id).withStdOut(true).withStdErr(true).exec(logs).awaitCompletion(30,
					TimeUnit.SECONDS);
			return new ExecResult(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
		}
		catch (com.github.dockerjava.api.exception.DockerClientException e) {
			// awaitStatusCode가 시간 초과 시 이 예외를 던진다.
			throw new DockerOperationException("컨테이너 실행 timed out 또는 실패: " + e.getMessage(), e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new DockerOperationException("컨테이너 실행이 중단되었습니다.", e);
		}
		finally {
			if (id != null) {
				try {
					docker.removeContainerCmd(id).withForce(true).withRemoveVolumes(true).exec();
				}
				catch (RuntimeException ignored) {
					// 정리 실패가 원래 결과를 가리면 안 된다.
				}
			}
		}
	}

	@Override
	public String createAndStart(ContainerSpec spec) {
		ExposedPort port = ExposedPort.tcp(spec.containerPort());
		// bindIp만 주면 호스트 포트가 비어 Docker가 임의 포트를 고른다.
		HostConfig host = HostConfig.newHostConfig().withMemory(1073741824L).withNanoCPUs(1000000000L)
				.withPidsLimit(256L).withSecurityOpts(List.of("no-new-privileges"))
				.withPortBindings(new PortBinding(Ports.Binding.bindIp(spec.bindAddress()), port))
				.withBinds(new Bind(spec.volumeName(), new Volume(spec.volumeMountPath())));
		List<String> env = new ArrayList<>();
		spec.env().forEach((k, v) -> env.add(k + "=" + v));
		String id = docker.createContainerCmd(spec.image()).withName(spec.name()).withEnv(env)
				.withLabels(spec.labels()).withExposedPorts(port).withHostConfig(host).exec().getId();
		try {
			docker.startContainerCmd(id).exec();
		}
		catch (RuntimeException e) {
			try {
				docker.removeContainerCmd(id).withForce(true).exec();
			}
			catch (RuntimeException ignored) {
			}
			throw e;
		}
		return id;
	}

	@Override
	public int hostPort(String containerId, int containerPort) {
		var ports = docker.inspectContainerCmd(containerId).exec().getNetworkSettings().getPorts().getBindings();
		Ports.Binding[] b = ports.get(ExposedPort.tcp(containerPort));
		if (b == null || b.length == 0 || b[0].getHostPortSpec() == null) {
			throw new DockerOperationException("컨테이너 포트 " + containerPort + "에 연결된 호스트 포트가 없습니다.");
		}
		return Integer.parseInt(b[0].getHostPortSpec());
	}

	@Override
	public boolean isRunning(String containerId) {
		try {
			return Boolean.TRUE.equals(docker.inspectContainerCmd(containerId).exec().getState().getRunning());
		}
		catch (NotFoundException e) {
			return false;
		}
	}

	@Override
	public ExecResult exec(String containerId, List<String> cmd, Duration timeout) {
		return exec(containerId, cmd, null, timeout);
	}

	@Override
	public ExecResult exec(String containerId, List<String> cmd, String workDir, Duration timeout) {
		var create = docker.execCreateCmd(containerId).withUser(STUDENT_USER).withAttachStdout(true)
				.withAttachStderr(true).withCmd(cmd.toArray(new String[0]));
		if (workDir != null) {
			create.withWorkingDir(workDir);
		}
		String execId = create.exec().getId();
		var out = new ByteArrayOutputStream();
		var err = new ByteArrayOutputStream();
		var cb = new FrameCollector(out, err);
		try {
			docker.execStartCmd(execId).exec(cb);
			if (!cb.awaitCompletion(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				cb.close();
				throw new DockerOperationException("exec timed out after " + timeout.toSeconds() + "s: " + cmd);
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new DockerOperationException("exec가 중단되었습니다.", e);
		}
		catch (java.io.IOException e) {
			throw new DockerOperationException("exec 정리 실패", e);
		}
		Long code = docker.inspectExecCmd(execId).exec().getExitCodeLong();
		return new ExecResult(code == null ? -1 : code.intValue(), out.toString(StandardCharsets.UTF_8),
				err.toString(StandardCharsets.UTF_8));
	}

	@Override
	public void restart(String containerId) {
		docker.restartContainerCmd(containerId).exec();
	}

	@Override
	public void stop(String containerId) {
		docker.stopContainerCmd(containerId).withTimeout(5).exec();
	}

	@Override
	public void remove(String containerId) {
		try {
			// 볼륨은 학생 코드이므로 남긴다(removeVolumes=false).
			docker.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(false).exec();
		}
		catch (NotFoundException ignored) {
			// 이미 없으면 목적을 이룬 것이다. 재시도·중복 호출이 안전해야 한다.
		}
		catch (RuntimeException e) {
			throw failure("컨테이너 삭제 실패", containerId, e);
		}
	}

	@Override
	public void removeVolume(String volumeName) {
		try {
			docker.removeVolumeCmd(volumeName).exec();
		}
		catch (NotFoundException ignored) {
			// 이미 없으면 목적을 이룬 것이다.
		}
		catch (RuntimeException e) {
			// 컨테이너가 붙어 있으면 409. 조용히 넘기면 학생 코드가 든 볼륨이 남은 줄 모르고 지운 것으로 보고된다.
			throw failure("볼륨 삭제 실패", volumeName, e);
		}
	}

	@Override
	public boolean containerExists(String containerId) {
		try {
			docker.inspectContainerCmd(containerId).exec();
			return true;
		}
		catch (NotFoundException e) {
			return false;
		}
		catch (RuntimeException e) {
			throw failure("컨테이너 확인 실패", containerId, e);
		}
	}

	@Override
	public boolean volumeExists(String volumeName) {
		try {
			docker.inspectVolumeCmd(volumeName).exec();
			return true;
		}
		catch (NotFoundException e) {
			return false;
		}
		catch (RuntimeException e) {
			throw failure("볼륨 확인 실패", volumeName, e);
		}
	}

	@Override
	public void removeImage(String image) {
		try {
			// noPrune: 지우는 이미지의 태그 없는 부모까지 따라 지우면 이 서버가 만들지 않은 이미지가 사라질 수 있다.
			docker.removeImageCmd(image).withForce(true).withNoPrune(true).exec();
		}
		catch (NotFoundException ignored) {
			// 이미 없으면 목적을 이룬 것이다.
		}
		catch (RuntimeException e) {
			throw failure("이미지 삭제 실패", image, e);
		}
	}

	@Override
	public InputStream copyArchive(String containerId, String path) {
		return docker.copyArchiveFromContainerCmd(containerId, path).exec();
	}

	/** 다중화된 로그 프레임을 stdout/stderr로 나눠 담는다. */
	private static final class FrameCollector extends ResultCallback.Adapter<Frame> {
		private final ByteArrayOutputStream out;
		private final ByteArrayOutputStream err;

		FrameCollector(ByteArrayOutputStream out, ByteArrayOutputStream err) {
			this.out = out;
			this.err = err;
		}

		@Override
		public void onNext(Frame f) {
			byte[] p = f.getPayload();
			if (f.getStreamType() == StreamType.STDERR) {
				err.write(p, 0, p.length);
			}
			else {
				out.write(p, 0, p.length);
			}
		}
	}
}
