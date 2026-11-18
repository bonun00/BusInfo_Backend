package bonun.bustime_backend.alert;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Discord 웹훅으로 알림을 보낸다.
 * 별도 스레드에서 보내서 스케줄러를 막지 않고, 전송에 실패해도 서비스에는 영향을 주지 않는다.
 * 웹훅 URL이 없으면(로컬 등) 로그로만 남긴다.
 */
@Component
@Slf4j
public class DiscordNotifier implements Notifier {

	private static final int MAX_LENGTH = 1900; // Discord 메시지 최대 2000자
	private static final int QUEUE_CAPACITY = 20;

	private final RestTemplate restTemplate;
	private final String webhookUrl;
	// Discord가 오래 막혀도 쌓이지 않도록 대기열 크기를 제한하고, 넘치면 버린다
	private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
		new ArrayBlockingQueue<>(QUEUE_CAPACITY),
		runnable -> {
			Thread thread = new Thread(runnable, "discord-notifier");
			thread.setDaemon(true);
			return thread;
		},
		(runnable, pool) -> log.warn("Discord 알림 대기열이 가득 차서 알림을 버렸습니다"));

	public DiscordNotifier(RestTemplateBuilder builder, @Value("${alert.discord.webhook-url:}") String webhookUrl) {
		this.restTemplate = builder
			.connectTimeout(Duration.ofSeconds(3))
			.readTimeout(Duration.ofSeconds(3))
			.build();
		this.webhookUrl = webhookUrl;
	}

	@Override
	public void send(String message) {
		String content = message.length() > MAX_LENGTH ? message.substring(0, MAX_LENGTH) + "…" : message;
		if (webhookUrl == null || webhookUrl.isBlank()) {
			log.warn("[알림 - 웹훅 미설정] {}", content);
			return;
		}
		executor.execute(() -> {
			try {
				restTemplate.postForEntity(webhookUrl, Map.of("content", content), Void.class);
			} catch (RuntimeException e) {
				log.warn("Discord 알림 전송 실패: {}", describe(e));
			}
		});
	}

	// 예외 메시지에는 토큰이 들어간 웹훅 URL이 포함되므로 남기지 않는다
	static String describe(RuntimeException e) {
		if (e instanceof HttpStatusCodeException http) {
			return "HTTP " + http.getStatusCode().value();
		}
		return e.getClass().getSimpleName();
	}

	@PreDestroy
	void shutdown() {
		executor.shutdown();
	}
}
