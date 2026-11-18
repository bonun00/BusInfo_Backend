package bonun.bustime_backend.alert;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import bonun.bustime_backend.service.PublicApiException;
import lombok.extern.slf4j.Slf4j;

/**
 * 스케줄러의 공공 API 갱신 상태를 보고 장애·복구를 알린다.
 *
 * - 즉시 조치 필요: 재시도해도 낫지 않는 에러(한도 초과, 키 문제 등). 같은 종류는 30분에 한 번만 보낸다.
 * - 장애 발생: 운영시간 중 stale-after 동안 갱신 성공이 하나도 없을 때. 상태가 바뀔 때만 보낸다.
 * - 복구: 장애 상태에서 성공이 다시 들어왔을 때. 장애 지속 시간을 함께 보낸다.
 *
 * 운영시간이 아니면 감시를 멈추고, 다시 시작할 때 기준 시각을 새로 잡는다(야간 중지를 장애로 오탐하지 않도록).
 * 장애 상태로 운영시간이 끝나면 그 사실을 알리고 장애 상태를 정리한다(다음 날 아침 '수백 분 장애 후 복구'로 잘못 알리지 않도록).
 */
@Component
@Slf4j
public class RefreshHealthMonitor {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
		.withZone(ZoneId.of("Asia/Seoul"));
	// 429처럼 성공과 섞여 나오는 에러가 매번 다시 알려지지 않도록 성공 여부와 상관없이 시간으로만 묶는다
	private static final Duration FATAL_REPEAT_AFTER = Duration.ofMinutes(30);

	private final Notifier notifier;
	private final Clock clock;
	private final Duration staleAfter;

	private boolean active;
	private Instant lastSuccessAt;   // 운영 재개 시각 또는 마지막 성공 시각
	private Instant downSince;       // 장애 상태일 때만 값이 있다
	private String lastError;
	private final Map<String, Instant> fatalNotifiedAt = new HashMap<>();

	public RefreshHealthMonitor(Notifier notifier, Clock clock,
		@Value("${alert.stale-after:5m}") Duration staleAfter) {
		this.notifier = notifier;
		this.clock = clock;
		this.staleAfter = staleAfter;
	}

	// 스케줄러가 운영시간이 아닐 때 호출
	public synchronized void pause() {
		if (active && downSince != null) {
			Instant now = clock.instant();
			long minutes = Duration.between(downSince, now).toMinutes();
			notifier.send("⏸️ [BuStory] 운영시간 종료로 감시를 멈춥니다 — 장애 상태로 종료\n"
				+ "장애 지속: 약 " + minutes + "분 (" + TIME.format(downSince) + " ~ " + TIME.format(now) + ")\n"
				+ "운영 재개 후에도 갱신이 안 되면 다시 알립니다");
			downSince = null;
		}
		active = false;
	}

	// 스케줄러가 운영시간일 때 매 tick 호출. 멈춰 있다가 재개되면 기준 시각을 지금으로 잡는다
	public synchronized void resume() {
		if (!active) {
			active = true;
			lastSuccessAt = clock.instant();
			lastError = null;
		}
	}

	public synchronized void recordSuccess() {
		Instant now = clock.instant();
		lastSuccessAt = now;
		if (downSince != null) {
			long minutes = Duration.between(downSince, now).toMinutes();
			notifier.send("✅ [BuStory] 공공 API 갱신 복구\n"
				+ "장애 지속: 약 " + minutes + "분 (" + TIME.format(downSince) + " ~ " + TIME.format(now) + ")");
			log.info("공공 API 갱신 복구 (장애 약 {}분)", minutes);
			downSince = null;
		}
	}

	public synchronized void recordFailure(String nodeId, Throwable error) {
		lastError = describe(error);
		String fatalKey = fatalKey(error);
		if (fatalKey == null) {
			return;
		}
		Instant now = clock.instant();
		Instant notifiedAt = fatalNotifiedAt.get(fatalKey);
		if (notifiedAt == null || Duration.between(notifiedAt, now).compareTo(FATAL_REPEAT_AFTER) >= 0) {
			fatalNotifiedAt.put(fatalKey, now);
			notifier.send("🚨 [BuStory] 즉시 조치 필요 — 재시도로 해결되지 않는 에러\n"
				+ "정류장: " + nodeId + "\n에러: " + lastError);
		}
	}

	// 매 tick 끝에 호출. 정상 → 장애로 바뀌는 순간에만 알린다
	public synchronized void evaluate() {
		if (!active || downSince != null || lastSuccessAt == null) {
			return;
		}
		Instant now = clock.instant();
		if (Duration.between(lastSuccessAt, now).compareTo(staleAfter) >= 0) {
			downSince = lastSuccessAt;
			notifier.send("⚠️ [BuStory] 공공 API 갱신 장애\n"
				+ staleAfter.toMinutes() + "분째 갱신 성공 없음 (마지막 성공 " + TIME.format(lastSuccessAt) + ")\n"
				+ "최근 에러: " + (lastError == null ? "없음 (호출 자체가 안 되고 있을 수 있음)" : lastError));
			log.warn("공공 API 갱신 장애 감지: 마지막 성공 {}", lastSuccessAt);
		}
	}

	public synchronized boolean isDown() {
		return downSince != null;
	}

	// 재시도해도 낫지 않는 에러면 종류를 돌려준다
	private static String fatalKey(Throwable error) {
		if (error instanceof PublicApiException e && e.isFatal()) {
			return "code:" + e.code();
		}
		if (error instanceof HttpStatusCodeException e) {
			int status = e.getStatusCode().value();
			if (status == 401 || status == 403 || status == 429) {
				return "http:" + status;
			}
		}
		return null;
	}

	private static String describe(Throwable error) {
		if (error instanceof HttpStatusCodeException e) {
			return "HTTP " + e.getStatusCode().value();
		}
		return error.getClass().getSimpleName() + ": " + error.getMessage();
	}
}
