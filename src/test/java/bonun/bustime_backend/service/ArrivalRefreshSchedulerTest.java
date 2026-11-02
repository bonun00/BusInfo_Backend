package bonun.bustime_backend.service;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import bonun.bustime_backend.config.BusProperties;
import bonun.bustime_backend.service.StopTimeClient.RefreshResult;

class ArrivalRefreshSchedulerTest {

	// 2026-08-25 10:00 (한국 시간)
	private final MutableClock clock = new MutableClock(Instant.parse("2026-08-25T01:00:00Z"));
	private final StopTimeClient stopTimeClient = mock(StopTimeClient.class);
	private final DailyCallCounter callCounter = mock(DailyCallCounter.class);
	private final ExecutorService executor = Executors.newFixedThreadPool(6);

	@BeforeEach
	void setUp() {
		when(stopTimeClient.refreshScheduled(anyString())).thenReturn(RefreshResult.UNCHANGED);
		when(callCounter.limitReached(anyLong())).thenReturn(false);
	}

	@AfterEach
	void tearDown() {
		executor.shutdownNow();
	}

	private ArrivalRefreshScheduler scheduler(boolean enabled, int stopCount) {
		List<BusProperties.Stop> stops = IntStream.rangeClosed(1, stopCount)
			.mapToObj(i -> new BusProperties.Stop("정류장" + i, "NODE" + i))
			.toList();
		BusProperties.Scheduler config = new BusProperties.Scheduler(enabled, Duration.ofSeconds(60), 6,
			"Asia/Seoul", "06:00", "23:00", 400_000, 2, Duration.ofSeconds(300));
		return new ArrivalRefreshScheduler(stopTimeClient, callCounter, new BusProperties(config, stops), clock,
			executor);
	}

	@Test
	void 한_회차에_최대_6개만_호출하고_나머지는_다음_회차로_넘긴다() {
		ArrivalRefreshScheduler scheduler = scheduler(true, 10);

		scheduler.tick();
		verify(stopTimeClient, times(6)).refreshScheduled(anyString());

		clock.advance(Duration.ofSeconds(5));
		scheduler.tick();
		verify(stopTimeClient, times(10)).refreshScheduled(anyString());

		clock.advance(Duration.ofSeconds(5));
		scheduler.tick();
		verify(stopTimeClient, times(10)).refreshScheduled(anyString());
	}

	@Test
	void 정류장마다_조회_주기가_지나면_다시_호출한다() {
		ArrivalRefreshScheduler scheduler = scheduler(true, 3);

		scheduler.tick();
		clock.advance(Duration.ofSeconds(59));
		scheduler.tick();
		verify(stopTimeClient, times(3)).refreshScheduled(anyString());

		clock.advance(Duration.ofSeconds(1));
		scheduler.tick();
		verify(stopTimeClient, times(6)).refreshScheduled(anyString());
		verify(stopTimeClient, times(2)).refreshScheduled("NODE1");
	}

	@Test
	void 꺼져_있으면_호출하지_않는다() {
		scheduler(false, 3).tick();

		verify(stopTimeClient, never()).refreshScheduled(anyString());
	}

	@Test
	void 운영_시간이_아니면_호출하지_않는다() {
		clock.set(Instant.parse("2026-08-25T14:30:00Z")); // 23:30 (한국 시간)

		scheduler(true, 3).tick();

		verify(stopTimeClient, never()).refreshScheduled(anyString());
	}

	@Test
	void 일일_호출_한도에_도달하면_호출하지_않는다() {
		Mockito.reset(callCounter);
		when(callCounter.limitReached(anyLong())).thenReturn(true);

		scheduler(true, 3).tick();

		verify(stopTimeClient, never()).refreshScheduled(anyString());
	}

	private static final class MutableClock extends Clock {

		private Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		void advance(Duration duration) {
			instant = instant.plus(duration);
		}

		void set(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return Clock.fixed(instant, zone);
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}
