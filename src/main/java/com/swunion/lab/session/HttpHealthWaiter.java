package com.swunion.lab.session;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class HttpHealthWaiter implements HealthWaiter {

	// 기본 HTTP/2 h2c 업그레이드 헤더를 보내면 code-server가 websocket upgrade로 보고 응답하지 않으므로 1.1로 고정한다.
	// 호출마다 클라이언트를 만들어 닫는다. 공유 클라이언트는 keep-alive 연결을 오래 열어 두는데, 그 연결이
	// 컨테이너 안 /proc/net/tcp에 ESTABLISHED로 보여 학생이 접속하기 전부터 "IDE 접속"으로 오판된다.
	private HttpClient newClient() {
		return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1)).build();
	}

	@Override
	public boolean awaitHealthy(int hostPort, Duration timeout) {
		try (HttpClient client = newClient()) {
			return await(client, hostPort, timeout);
		}
	}

	private boolean await(HttpClient client, int hostPort, Duration timeout) {
		// ideBindAddress가 0.0.0.0이어도 서버 자신은 루프백으로 확인하면 된다.
		HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + hostPort + "/healthz"))
				.timeout(Duration.ofSeconds(2)).GET().build();
		long deadline = System.nanoTime() + timeout.toNanos();
		while (true) {
			try {
				if (client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
					return true;
				}
			}
			catch (java.io.IOException e) {
				// 아직 뜨는 중
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
			if (System.nanoTime() >= deadline) {
				return false;
			}
			try {
				Thread.sleep(500);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
	}
}
