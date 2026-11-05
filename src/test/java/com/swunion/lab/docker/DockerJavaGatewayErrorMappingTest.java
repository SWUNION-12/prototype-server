package com.swunion.lab.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectVolumeCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.RemoveImageCmd;
import com.github.dockerjava.api.command.RemoveVolumeCmd;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.exception.NotFoundException;
import org.junit.jupiter.api.Test;

/**
 * Docker 없이 오류 분류만 확인한다. 404("이미 없음")만 성공으로 보고, 나머지는 DockerOperationException이어야 한다.
 * 실제 데몬이 409를 돌려주는 경우는 DockerJavaGatewayIT가 본다(볼륨 사용 중).
 */
class DockerJavaGatewayErrorMappingTest {

	// 데몬 본문은 메시지로 새어 나가면 안 된다. 호출자가 응답·로그에 그대로 싣는다.
	static final String DAEMON_BODY = "daemon-body-marker";

	final DockerClient client = mock(DockerClient.class);
	final DockerJavaGateway gw = new DockerJavaGateway(client);

	@Test
	void removeImageForcesWithoutPruningParentsAndIgnoresMissing() {
		RemoveImageCmd cmd = mock(RemoveImageCmd.class);
		when(client.removeImageCmd("img:1")).thenReturn(cmd);
		when(cmd.withForce(true)).thenReturn(cmd);
		when(cmd.withNoPrune(true)).thenReturn(cmd);
		doThrow(new NotFoundException("no such image")).when(cmd).exec();

		assertThatCode(() -> gw.removeImage("img:1")).doesNotThrowAnyException();

		verify(cmd).withForce(true);
		verify(cmd).withNoPrune(true);
	}

	@Test
	void removeImageConflictBecomesOperationExceptionWithoutDaemonBody() {
		RemoveImageCmd cmd = mock(RemoveImageCmd.class);
		when(client.removeImageCmd("img:1")).thenReturn(cmd);
		when(cmd.withForce(true)).thenReturn(cmd);
		when(cmd.withNoPrune(true)).thenReturn(cmd);
		ConflictException cause = new ConflictException(DAEMON_BODY);
		doThrow(cause).when(cmd).exec();

		assertThatThrownBy(() -> gw.removeImage("img:1")).isInstanceOf(DockerOperationException.class)
				.hasMessageContaining("img:1").hasMessageContaining("409").hasMessageNotContaining(DAEMON_BODY)
				.hasCause(cause);
	}

	@Test
	void removeImageServerErrorBecomesOperationException() {
		RemoveImageCmd cmd = mock(RemoveImageCmd.class);
		when(client.removeImageCmd("img:1")).thenReturn(cmd);
		when(cmd.withForce(true)).thenReturn(cmd);
		when(cmd.withNoPrune(true)).thenReturn(cmd);
		doThrow(new InternalServerErrorException(DAEMON_BODY)).when(cmd).exec();

		assertThatThrownBy(() -> gw.removeImage("img:1")).isInstanceOf(DockerOperationException.class)
				.hasMessageContaining("500").hasMessageNotContaining(DAEMON_BODY);
	}

	@Test
	void removeContainerIgnoresMissingButNotOtherFailures() {
		RemoveContainerCmd cmd = mock(RemoveContainerCmd.class);
		when(client.removeContainerCmd("c1")).thenReturn(cmd);
		when(cmd.withForce(true)).thenReturn(cmd);
		when(cmd.withRemoveVolumes(false)).thenReturn(cmd);

		doThrow(new NotFoundException("gone")).when(cmd).exec();
		assertThatCode(() -> gw.remove("c1")).doesNotThrowAnyException();

		doThrow(new ConflictException(DAEMON_BODY)).when(cmd).exec();
		assertThatThrownBy(() -> gw.remove("c1")).isInstanceOf(DockerOperationException.class)
				.hasMessageContaining("c1").hasMessageContaining("409").hasMessageNotContaining(DAEMON_BODY);
	}

	@Test
	void removeVolumeIgnoresMissingButNotOtherFailures() {
		RemoveVolumeCmd cmd = mock(RemoveVolumeCmd.class);
		when(client.removeVolumeCmd("v1")).thenReturn(cmd);

		doThrow(new NotFoundException("gone")).when(cmd).exec();
		assertThatCode(() -> gw.removeVolume("v1")).doesNotThrowAnyException();

		doThrow(new ConflictException(DAEMON_BODY)).when(cmd).exec();
		assertThatThrownBy(() -> gw.removeVolume("v1")).isInstanceOf(DockerOperationException.class)
				.hasMessageContaining("v1").hasMessageContaining("409").hasMessageNotContaining(DAEMON_BODY);
	}

	@Test
	void unexpectedRuntimeFailureIsWrappedToo() {
		RemoveVolumeCmd cmd = mock(RemoveVolumeCmd.class);
		when(client.removeVolumeCmd("v1")).thenReturn(cmd);
		doThrow(new IllegalStateException(DAEMON_BODY)).when(cmd).exec();

		assertThatThrownBy(() -> gw.removeVolume("v1")).isInstanceOf(DockerOperationException.class)
				.hasMessageContaining("v1").hasMessageNotContaining(DAEMON_BODY);
	}

	@Test
	void containerExistsIsFalseOn404AndThrowsOtherwise() {
		InspectContainerCmd cmd = mock(InspectContainerCmd.class);
		when(client.inspectContainerCmd("c1")).thenReturn(cmd);

		doThrow(new NotFoundException("gone")).when(cmd).exec();
		assertThat(gw.containerExists("c1")).isFalse();

		doThrow(new InternalServerErrorException(DAEMON_BODY)).when(cmd).exec();
		assertThatThrownBy(() -> gw.containerExists("c1")).isInstanceOf(DockerOperationException.class)
				.hasMessageContaining("c1").hasMessageContaining("500").hasMessageNotContaining(DAEMON_BODY);
	}

	@Test
	void volumeExistsIsFalseOn404AndThrowsOtherwise() {
		InspectVolumeCmd cmd = mock(InspectVolumeCmd.class);
		when(client.inspectVolumeCmd("v1")).thenReturn(cmd);

		doThrow(new NotFoundException("gone")).when(cmd).exec();
		assertThat(gw.volumeExists("v1")).isFalse();

		doThrow(new InternalServerErrorException(DAEMON_BODY)).when(cmd).exec();
		assertThatThrownBy(() -> gw.volumeExists("v1")).isInstanceOf(DockerOperationException.class)
				.hasMessageContaining("v1").hasMessageContaining("500").hasMessageNotContaining(DAEMON_BODY);
	}
}
