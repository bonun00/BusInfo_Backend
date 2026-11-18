package bonun.bustime_backend.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import bonun.bustime_backend.service.PublicApiException;

class RefreshHealthMonitorTest {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-11-18T01:00:00Z"));
	private final List<String> sent = new ArrayList<>();
	private RefreshHealthMonitor monitor;

	@BeforeEach
	void setUp() {
		monitor = new RefreshHealthMonitor(sent::add, clock, Duration.ofMinutes(5));
		monitor.resume();
	}

	@Test
	void 성공이_계속되면_알림이_없다() {
		for (int i = 0; i < 10; i++) {
			clock.advance(Duration.ofMinutes(1));
			monitor.recordSuccess();
			monitor.evaluate();
		}
		assertThat(sent).isEmpty();
	}

	@Test
	void 오분째_성공이_없으면_장애_알림을_한_번만_보낸다() {
		monitor.recordFailure("N1", new RuntimeException("timeout"));
		clock.advance(Duration.ofMinutes(4));
		monitor.evaluate();
		assertThat(sent).isEmpty();

		clock.advance(Duration.ofMinutes(1));
		monitor.evaluate();
		clock.advance(Duration.ofMinutes(10));
		monitor.evaluate();

		assertThat(sent).hasSize(1);
		assertThat(sent.get(0)).contains("장애").contains("timeout");
		assertThat(monitor.isDown()).isTrue();
	}

	@Test
	void 장애_중_성공하면_복구_알림을_보낸다() {
		clock.advance(Duration.ofMinutes(6));
		monitor.evaluate();
		clock.advance(Duration.ofMinutes(10));
		monitor.recordSuccess();

		assertThat(sent).hasSize(2);
		assertThat(sent.get(1)).contains("복구").contains("16분");
		assertThat(monitor.isDown()).isFalse();
	}

	@Test
	void 야간에_멈췄다가_재개하면_장애로_보지_않는다() {
		monitor.pause();
		clock.advance(Duration.ofHours(7));
		monitor.evaluate();          // 멈춰 있는 동안은 판단하지 않음
		monitor.resume();            // 재개 시점이 새 기준
		clock.advance(Duration.ofMinutes(1));
		monitor.evaluate();

		assertThat(sent).isEmpty();
	}

	@Test
	void 재시도로_안_낫는_에러는_즉시_알리고_같은_종류는_한_번만_보낸다() {
		monitor.recordFailure("N1", new PublicApiException("22", "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR"));
		monitor.recordFailure("N2", new PublicApiException("22", "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR"));
		monitor.recordFailure("N3", new HttpClientErrorException(HttpStatus.FORBIDDEN));

		assertThat(sent).hasSize(2);
		assertThat(sent.get(0)).contains("즉시 조치").contains("22");
		assertThat(sent.get(1)).contains("HTTP 403");
	}

	@Test
	void 장애_상태로_운영시간이_끝나면_알리고_다음날_복구로_잘못_알리지_않는다() {
		clock.advance(Duration.ofMinutes(6));
		monitor.evaluate();          // 장애
		monitor.pause();             // 23:00 운영 종료
		monitor.pause();             // 운영시간 밖에서는 매 tick 호출된다
		clock.advance(Duration.ofHours(7));
		monitor.resume();
		monitor.recordSuccess();     // 다음 날 첫 성공

		assertThat(sent).hasSize(2);
		assertThat(sent.get(1)).contains("운영시간 종료").contains("6분");
		assertThat(monitor.isDown()).isFalse();
	}

	@Test
	void 재시도로_안_낫는_에러는_성공이_섞여도_삼십분_안에는_다시_알리지_않는다() {
		monitor.recordFailure("N1", new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));
		monitor.recordSuccess();
		clock.advance(Duration.ofMinutes(29));
		monitor.recordFailure("N2", new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));
		assertThat(sent).hasSize(1);

		clock.advance(Duration.ofMinutes(1));
		monitor.recordFailure("N3", new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));
		assertThat(sent).hasSize(2);
	}

	@Test
	void 일시적_에러는_즉시_알리지_않는다() {
		monitor.recordFailure("N1", new PublicApiException("99", "UNKNOWN_ERROR"));
		monitor.recordFailure("N1", new RuntimeException("connection reset"));

		assertThat(sent).isEmpty();
	}

	private static final class MutableClock extends Clock {
		private Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
