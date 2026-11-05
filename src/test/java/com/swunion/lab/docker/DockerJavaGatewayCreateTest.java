package com.swunion.lab.docker;

import static org.assertj.core.api.Assertions.assertThat;
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
		CreateContainerCmd create = mock(CreateContainerCmd.class, RETURNS_SELF);
		CreateContainerResponse response = mock(CreateContainerResponse.class);
		StartContainerCmd start = mock(StartContainerCmd.class);
		when(client.createContainerCmd("img:1")).thenReturn(create);
		when(create.exec()).thenReturn(response);
		when(response.getId()).thenReturn("cid-1");
		when(client.startContainerCmd("cid-1")).thenReturn(start);
		DockerJavaGateway gw = new DockerJavaGateway(client);

		String id = gw.createAndStart(new ContainerSpec("name-1", "img:1", Map.of("PASSWORD", "pw"), "vol-1",
				"/home/coder/project", "127.0.0.1", 8080, Map.of()));

		assertThat(id).isEqualTo("cid-1");
		ArgumentCaptor<HostConfig> host = ArgumentCaptor.forClass(HostConfig.class);
		verify(create).withHostConfig(host.capture());
		// 리눅스 Docker는 host.docker.internal을 기본으로 풀지 못한다. 컨테이너 안 확장이 서버에 닿으려면 필요하다.
		assertThat(host.getValue().getExtraHosts()).containsExactly("host.docker.internal:host-gateway");
		// 기존 제한과 포트 바인딩은 그대로다.
		assertThat(host.getValue().getMemory()).isEqualTo(1073741824L);
		assertThat(host.getValue().getPidsLimit()).isEqualTo(256L);
		assertThat(host.getValue().getPortBindings().getBindings()).hasSize(1);
		verify(start).exec();
	}
}
