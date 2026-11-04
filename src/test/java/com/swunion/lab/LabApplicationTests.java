package com.swunion.lab;

import static org.assertj.core.api.Assertions.assertThat;

import com.swunion.lab.config.LabProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// 테스트가 실제 .env를 읽지 않도록 config import를 존재하지 않는 파일로 돌린다.
@SpringBootTest(properties = {
		"spring.config.import=optional:file:./build/no-such.env[.properties]",
		"spring.datasource.url=jdbc:h2:mem:labtest"
})
class LabApplicationTests {

	@Autowired
	LabProperties props;

	@Test
	void contextLoads() {
	}

	@Test
	void bindsDefaults() {
		assertThat(props.instructorUsername()).isEqualTo("instructor");
		assertThat(props.llmModel()).isEqualTo("claude-haiku-5-5");
		assertThat(props.checkIntervalSeconds()).isEqualTo(15);
		assertThat(props.publicBaseUrl()).isEqualTo("http://localhost:8080");
		assertThat(props.ideBindAddress()).isEqualTo("127.0.0.1");
		// Spring의 Path 변환이 "./data"를 "data"로 정규화하므로 정규화한 값끼리 비교한다.
		assertThat(props.dataDir().normalize()).isEqualTo(Path.of("data"));
		assertThat(props.baseImage()).isEqualTo("swunion/lab-base:1");
	}
}
