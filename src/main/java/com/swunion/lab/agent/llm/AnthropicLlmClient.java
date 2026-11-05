package com.swunion.lab.agent.llm;

import com.swunion.lab.agent.llm.LlmTypes.*;
import com.swunion.lab.config.ApiKeyResolver;
import com.swunion.lab.config.LabProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Anthropic Messages API 클라이언트. 재시도는 하지 않는다(에이전트 루프가 한 번 재시도). */
public class AnthropicLlmClient implements LlmClient {

	private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);
	static final String BASE_URL = "https://api.anthropic.com";

	private final RestClient rest;
	private final ApiKeyResolver keys;
	private final LabProperties props;
	private final JsonMapper json = JsonMapper.builder().build();

	public AnthropicLlmClient(RestClient rest, ApiKeyResolver keys, LabProperties props) {
		this.rest = rest;
		this.keys = keys;
		this.props = props;
	}

	/** 연결 10초, 읽기 60초 타임아웃을 건 RestClient. 테스트는 이 팩토리 없이 Builder에 Mock 서버를 바인딩한다. */
	public static RestClient newRestClient(RestClient.Builder builder) {
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(Duration.ofSeconds(60));
		return builder.requestFactory(factory).build();
	}

	@Override
	public LlmResponse send(String system, List<Turn> turns, List<ToolSpec> tools) {
		// 호출 시점에 읽어서 서버 실행 중에 .env에 키를 추가해도 동작한다.
		String key = keys.resolve().orElseThrow(() -> new LlmException("API 키 없음"));
		Map<String, Object> body = MessagesProtocol.buildBody(props.llmModel(), system, turns, tools);
		body.put("model", props.llmModel());
		String raw;
		try {
			raw = rest.post().uri(BASE_URL + "/v1/messages").contentType(MediaType.APPLICATION_JSON)
					.header("x-api-key", key).header("anthropic-version", "2023-06-01").body(body).retrieve()
					.body(String.class);
		} catch (RestClientResponseException e) {
			throw new LlmException(describeHttpError(e));
		} catch (RestClientException e) {
			// 원인 예외에 요청 정보가 섞일 수 있어 클래스명만 남기고 cause는 붙이지 않는다.
			throw new LlmException("LLM 호출 실패: " + e.getClass().getSimpleName());
		}
		log.debug("LLM 응답 수신 model={}", props.llmModel());
		return MessagesProtocol.parse(raw);
	}

	private String describeHttpError(RestClientResponseException e) {
		String msg = "LLM HTTP " + e.getStatusCode().value();
		try {
			JsonNode type = json.readTree(e.getResponseBodyAsString()).path("error").path("type");
			if (type.isString()) {
				msg += " (" + type.asString() + ")";
			}
		} catch (RuntimeException ignored) {
			// 본문이 JSON이 아니면 상태 코드만 알린다.
		}
		return msg;
	}
}
