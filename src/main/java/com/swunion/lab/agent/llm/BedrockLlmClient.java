package com.swunion.lab.agent.llm;

import com.swunion.lab.agent.llm.LlmTypes.*;
import com.swunion.lab.config.LabProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Amazon Bedrock InvokeModel로 Claude를 부르는 클라이언트. EC2 인스턴스 역할(기본 자격 증명 체인)로 인증하므로
 * Anthropic API 키가 필요 없다. 본문과 응답 해석은 AnthropicLlmClient와 같은 MessagesProtocol을 쓴다.
 * 재시도는 하지 않는다(에이전트 루프가 한 번 재시도).
 */
public class BedrockLlmClient implements LlmClient {

	private static final Logger log = LoggerFactory.getLogger(BedrockLlmClient.class);
	static final String ANTHROPIC_VERSION = "bedrock-2023-05-31";

	private final BedrockRuntimeClient bedrock;
	private final LabProperties props;
	private final JsonMapper json = JsonMapper.builder().build();

	public BedrockLlmClient(BedrockRuntimeClient bedrock, LabProperties props) {
		this.bedrock = bedrock;
		this.props = props;
	}

	/**
	 * 연결 10초, 읽기 60초, 호출 전체 90초 타임아웃. SDK 자체 재시도는 끈다.
	 * HTTP 클라이언트는 jar를 줄이려고 URL 연결 방식을 쓴다. 자격 증명은 만들 때가 아니라 첫 호출 때 찾으므로
	 * 자격 증명이 없는 개발 머신에서도 이 팩토리까지는 실패하지 않는다.
	 */
	public static BedrockRuntimeClient newRuntimeClient(String region) {
		return BedrockRuntimeClient.builder().region(Region.of(region))
				.httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(10))
						.socketTimeout(Duration.ofSeconds(60)))
				.overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(90))
						.apiCallAttemptTimeout(Duration.ofSeconds(80)).retryStrategy(b -> b.maxAttempts(1)))
				.build();
	}

	@Override
	public LlmResponse send(String system, List<Turn> turns, List<ToolSpec> tools) {
		String model = props.llmModel();
		Map<String, Object> body = MessagesProtocol.buildBody(model, system, turns, tools);
		// Bedrock은 모델을 요청 경로(modelId)로 받고 본문의 model 필드를 허용하지 않는다.
		body.put("anthropic_version", ANTHROPIC_VERSION);
		String raw;
		try {
			InvokeModelRequest request = InvokeModelRequest.builder().modelId(model).contentType("application/json")
					.accept("application/json").body(SdkBytes.fromUtf8String(json.writeValueAsString(body))).build();
			InvokeModelResponse response = bedrock.invokeModel(request);
			raw = response.body().asUtf8String();
		} catch (AwsServiceException e) {
			throw new LlmException(describeServiceError(e));
		} catch (SdkException e) {
			// 자격 증명 없음, 타임아웃, 연결 실패. 원인 예외에 요청 정보가 섞일 수 있어 클래스명만 남기고 cause는 붙이지 않는다.
			throw new LlmException("LLM 호출 실패: " + e.getClass().getSimpleName());
		} catch (RuntimeException e) {
			throw new LlmException("LLM 호출 실패: " + e.getClass().getSimpleName());
		}
		log.debug("LLM 응답 수신 provider=bedrock model={}", model);
		return MessagesProtocol.parse(raw);
	}

	private static String describeServiceError(AwsServiceException e) {
		String msg = "LLM Bedrock HTTP " + e.statusCode();
		if (e.awsErrorDetails() != null && e.awsErrorDetails().errorCode() != null) {
			msg += " (" + e.awsErrorDetails().errorCode() + ")";
		}
		return msg;
	}
}
