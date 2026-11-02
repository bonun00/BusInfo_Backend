package bonun.bustime_backend.config;

import java.time.Duration;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

	// JDK HttpClient: 커넥션을 재사용해 TLS 핸드셰이크를 반복하지 않음
	// 0.1 CPU에서 첫 호출이 수 초 걸린 적이 있어 타임아웃을 넉넉히 둠
	@Bean
	public RestTemplate publicApiRestTemplate(RestTemplateBuilder builder) {
		return builder
			.requestFactoryBuilder(ClientHttpRequestFactoryBuilder.jdk())
			.connectTimeout(Duration.ofSeconds(5))
			.readTimeout(Duration.ofSeconds(5))
			.build();
	}
}
