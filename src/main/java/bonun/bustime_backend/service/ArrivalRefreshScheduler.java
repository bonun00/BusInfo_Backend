package bonun.bustime_backend.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import bonun.bustime_backend.alert.Notifier;
import bonun.bustime_backend.alert.RefreshHealthMonitor;
import bonun.bustime_backend.config.BusProperties;
import bonun.bustime_backend.service.StopTimeClient.RefreshResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 등록된 정류장을 poll-interval마다 한 번씩 조회해 도착 정보를 갱신한다.
 *
 * tick마다 조회할 차례가 된 정류장을 오래 기다린 순서로 최대 max-calls-per-tick개 골라 동시에 호출한다.
 * 한꺼번에 몰리지 않도록 나눠 부르기 때문에, 처음이나 운영 시간이 다시 시작될 때도 자연스럽게 분산된다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ArrivalRefreshScheduler {

	private static final Duration SUMMARY_INTERVAL = Duration.ofMinutes(5);
	private static final long TASK_TIMEOUT_SECONDS = 15;

	private final StopTimeClient stopTimeClient;
	private final DailyCallCounter callCounter;
	private final BusProperties properties;
	private final Clock clock;
	private final ExecutorService arrivalRefreshExecutor;
	private final RefreshHealthMonitor healthMonitor;
	private final Notifier notifier;

	private final Map<String, Instant> nextPollAt = new ConcurrentHashMap<>();

	private int changed;
	private int unchanged;
	private int failed;
	private Instant lastSummaryAt;
	private boolean limitWarned;

	@Scheduled(fixedDelayString = "${bus.scheduler.tick:PT5S}", initialDelayString = "${bus.scheduler.initial-delay:PT30S}")
	public void tick() {
		BusProperties.Scheduler config = properties.scheduler();
		if (!config.isActive(clock)) {
			healthMonitor.pause();
			return;
		}
		healthMonitor.resume();
		if (callCounter.limitReached(config.dailyCallLimit())) {
			if (!limitWarned) {
				log.warn("공공 API 일일 호출 수가 {}회에 도달해 스케줄러를 멈춥니다", config.dailyCallLimit());
				notifier.send("🚨 [BuStory] 공공 API 일일 호출 수가 " + config.dailyCallLimit() + "회에 도달해 스케줄러를 멈췄습니다");
				limitWarned = true;
			}
			return;
		}
		limitWarned = false;

		Instant now = clock.instant();
		List<String> due = properties.nodeIds().stream()
			.filter(nodeId -> !nextPollAt.getOrDefault(nodeId, Instant.EPOCH).isAfter(now))
			.sorted(Comparator.comparing(nodeId -> nextPollAt.getOrDefault(nodeId, Instant.EPOCH)))
			.limit(config.maxCallsPerTick())
			.toList();

		List<Future<RefreshResult>> futures = new ArrayList<>();
		for (String nodeId : due) {
			futures.add(arrivalRefreshExecutor.submit(() -> stopTimeClient.refreshScheduled(nodeId)));
		}

		for (int k = 0; k < due.size(); k++) {
			nextPollAt.put(due.get(k), now.plus(config.pollInterval()));
			try {
				record(futures.get(k).get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
			} catch (TimeoutException | ExecutionException e) {
				futures.get(k).cancel(true);
				failed++;
				healthMonitor.recordFailure(due.get(k), e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}

		healthMonitor.evaluate();
		logSummaryIfDue(now);
	}

	private void record(RefreshResult result) {
		switch (result) {
			case CHANGED -> changed++;
			case UNCHANGED -> unchanged++;
			case FAILED -> failed++;
		}
	}

	private void logSummaryIfDue(Instant now) {
		if (lastSummaryAt == null) {
			lastSummaryAt = now;
			return;
		}
		if (Duration.between(lastSummaryAt, now).compareTo(SUMMARY_INTERVAL) < 0) {
			return;
		}
		log.info("스케줄러 요약 (최근 {}분): 변경 {}건, 그대로 {}건, 실패 {}건, 오늘 공공 API 호출 {}회",
			SUMMARY_INTERVAL.toMinutes(), changed, unchanged, failed, callCounter.todayCount());
		changed = 0;
		unchanged = 0;
		failed = 0;
		lastSummaryAt = now;
	}
}
