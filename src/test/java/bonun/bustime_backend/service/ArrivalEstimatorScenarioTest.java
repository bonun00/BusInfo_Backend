package bonun.bustime_backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import bonun.bustime_backend.dto.ArrivalItem;

/**
 * tago_probe.py 측정 통계(갱신 간격 중앙값 122초, 범위 30~454초)로 만든 가상 시나리오에서
 * 기존 방식(조회 시각 + arrtime)과 변경 시점 기준 방식의 도착 예정 시각 오차를 비교한다.
 *
 * TAGO는 갱신 시각 u에 arrtime = (실제 도착 시각 - u)로 값을 바꾸고 다음 갱신까지 그대로 둔다고 가정한다.
 * 버스는 60초 간격으로 조회한다.
 */
class ArrivalEstimatorScenarioTest {

	private static final String NODE = "TSB390000116";
	private static final Instant T0 = Instant.parse("2026-08-25T06:00:00Z");
	private static final long POLL_INTERVAL = 60;
	private static final long POLL_OFFSET = 7;

	private record Result(long maxLegacyErrorSec, double maxNewErrorSec) {
	}

	private static List<Long> updatesWithGaps(long firstUpdate, long arrival, long... gaps) {
		List<Long> updates = new ArrayList<>();
		long u = firstUpdate;
		for (int k = 0; u < arrival; k++) {
			updates.add(u);
			u += gaps[k % gaps.length];
		}
		return updates;
	}

	private static Result simulate(List<Long> updates, long arrival) {
		ArrivalEstimator estimator = new ArrivalEstimator(Set.of(NODE), 2, Duration.ofSeconds(150));
		long maxLegacy = 0;
		double maxNew = 0;

		for (long t = POLL_OFFSET; t < arrival - 30; t += POLL_INTERVAL) {
			long now = t;
			Long latest = updates.stream().filter(u -> u <= now).reduce((a, b) -> b).orElse(null);
			List<ArrivalItem> items = new ArrayList<>();
			if (latest != null) {
				int remaining = (int)(arrival - latest);
				items.add(new ArrivalItem(NODE, "창원 T/R", "R113", "113", "농어촌버스", "일반차량", remaining,
					remaining / 120 + 1));
			}

			var estimate = estimator.estimate(NODE, items, T0.plusSeconds(t));
			if (latest == null) {
				continue;
			}

			long legacyPredicted = t + (arrival - latest);
			maxLegacy = Math.max(maxLegacy, Math.abs(legacyPredicted - arrival));

			long expireAt = estimate.arrivals().get(0).expireAt();
			double newError = Math.abs(expireAt - T0.plusSeconds(arrival).toEpochMilli()) / 1000.0;
			maxNew = Math.max(maxNew, newError);
		}
		return new Result(maxLegacy, maxNew);
	}

	@Test
	void 갱신_간격이_122초로_규칙적이면_오차가_조회_간격의_절반_안에_머문다() {
		long arrival = 1_500;
		Result result = simulate(updatesWithGaps(100, arrival, 122), arrival);

		assertThat(result.maxLegacyErrorSec()).isGreaterThan(POLL_INTERVAL);
		assertThat(result.maxNewErrorSec()).isLessThanOrEqualTo(POLL_INTERVAL / 2.0);
	}

	@Test
	void 갱신_간격이_30초에서_454초로_불규칙해도_오차가_조회_간격의_절반_안에_머문다() {
		long arrival = 2_400;
		Result result = simulate(updatesWithGaps(100, arrival, 30, 454, 122, 200, 61, 300), arrival);

		// 기존 방식은 값이 454초 동안 바뀌지 않는 구간에서 조회할 때마다 도착 예정 시각이 밀린다
		assertThat(result.maxLegacyErrorSec()).isGreaterThan(300);
		assertThat(result.maxNewErrorSec()).isLessThanOrEqualTo(POLL_INTERVAL / 2.0);
	}
}
