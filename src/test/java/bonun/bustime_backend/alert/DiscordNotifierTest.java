package bonun.bustime_backend.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

class DiscordNotifierTest {

	@Test
	void 전송_실패_로그에_웹훅_URL을_남기지_않는다() {
		ResourceAccessException error = new ResourceAccessException(
			"I/O error on POST request for \"https://discord.com/api/webhooks/123/secret-token\": timed out",
			new IOException("timed out"));

		assertThat(DiscordNotifier.describe(error))
			.isEqualTo("ResourceAccessException")
			.doesNotContain("secret-token");
	}

	@Test
	void HTTP_에러는_상태코드만_남긴다() {
		assertThat(DiscordNotifier.describe(new HttpClientErrorException(HttpStatus.NOT_FOUND))).isEqualTo("HTTP 404");
	}
}
