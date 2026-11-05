package com.swunion.lab.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.model.HostConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Docker 없이 컨테이너 생성 요청의 호스트 설정만 본다. 실제 데몬 동작은 DockerJavaGatewayIT가 본다. */
class DockerJavaGatewayCreateTest {

	@Test
	void everyContainerGetsTheHostGatewayEntry() {
		DockerClient client = mock(DockerClient.class);
		CreateContainerCmd create = stubCreate(client);

		String id = create(new DockerJavaGateway(client));

		assertThat(id).isEqualTo("cid-1");
		HostConfig host = capturedHost(create);
		// 리눅스 Docker는 host.docker.internal을 기본으로 풀지 못한다. 컨테이너 안 확장이 서버에 닿으려면 필요하다.
		assertThat(host.getExtraHosts()).containsExactly("host.docker.internal:host-gateway");
		// 메모리 기본 상한은 800MB다. 나머지 제한과 포트 바인딩은 그대로다.
		assertThat(host.getMemory()).isEqualTo(800L * 1024 * 1024);
		assertThat(host.getPidsLimit()).isEqualTo(256L);
		assertThat(host.getPortBindings().getBindings()).hasSize(1);
		verify(client.startContainerCmd("cid-1")).exec();
	}

	@Test
	void memoryLimitComesFromSettings() {
		DockerClient client = mock(DockerClient.class);
		CreateContainerCmd create = stubCreate(client);

		create(new DockerJavaGateway(client, 1200));

		assertThat(capturedHost(create).getMemory()).isEqualTo(1200L * 1024 * 1024);
	}

	@Test
	void tooSmallMemoryIsRejectedAtStartup() {
		DockerClient client = mock(DockerClient.class);

		assertThatThrownBy(() -> new DockerJavaGateway(client, 255)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("256MB");
	}

	private static CreateContainerCmd stubCreate(DockerClient client) {
		CreateContainerCmd create = mock(CreateContainerCmd.class, RETURNS_SELF);
		CreateContainerResponse response = mock(CreateContainerResponse.class);
		StartContainerCmd start = mock(StartContainerCmd.class);
		when(client.createContainerCmd("img:1")).thenReturn(create);
		when(create.exec()).thenReturn(response);
		when(response.getId()).thenReturn("cid-1");
		when(client.startContainerCmd("cid-1")).thenReturn(start);
		return create;
	}

	private static String create(DockerJavaGateway gw) {
		return gw.createAndStart(new ContainerSpec("name-1", "img:1", Map.of("PASSWORD", "pw"), "vol-1",
				"/home/coder/project", "127.0.0.1", 8080, Map.of()));
	}

	private static HostConfig capturedHost(CreateContainerCmd create) {
		ArgumentCaptor<HostConfig> host = ArgumentCaptor.forClass(HostConfig.class);
		verify(create).withHostConfig(host.capture());
		return host.getValue();
	}
}
