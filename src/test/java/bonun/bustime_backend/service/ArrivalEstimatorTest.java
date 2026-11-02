package bonun.bustime_backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import bonun.bustime_backend.dto.ArrivalInfo;
import bonun.bustime_backend.dto.ArrivalItem;
import bonun.bustime_backend.service.ArrivalEstimator.ChangeType;
import bonun.bustime_backend.service.ArrivalEstimator.Estimate;

class ArrivalEstimatorTest {

	private static final String NODE = "TSB390000116";
	private static final Instant T0 = Instant.parse("2026-08-25T00:00:00Z");

	private final ArrivalEstimator estimator = new ArrivalEstimator(Set.of(NODE), 2, Duration.ofSeconds(150));

	private static ArrivalItem bus(String routeNo, int arrTime, int stations) {
		return new ArrivalItem(NODE, "창원 T/R", "R" + routeNo, routeNo, "농어촌버스", "일반차량", arrTime, stations);
	}

	private static Instant at(long seconds) {
		return T0.plusSeconds(seconds);
	}

	private static long millis(long seconds) {
		return at(seconds).toEpochMilli();
	}

	@Test
	void 처음_조회는_조회_시각을_기준으로_계산한다() {
		Estimate estimate = estimator.estimate(NODE, List.of(bus("113", 600, 5)), at(0));

		assertThat(estimate.arrivals()).extracting(ArrivalInfo::expireAt).containsExactly(millis(600));
		assertThat(estimate.changes()).extracting(ArrivalEstimator.Change::type).containsExactly(ChangeType.APPEARED);
	}

	@Test
	void 값이_그대로면_도착_예정_시각을_유지한다() {
		estimator.estimate(NODE, List.of(bus("113", 600, 5)), at(0));

		Estimate estimate = estimator.estimate(NODE, List.of(bus("113", 600, 5)), at(60));

		assertThat(estimate.arrivals()).extracting(ArrivalInfo::expireAt).containsExactly(millis(600));
		assertThat(estimate.changed()).isFalse();
	}

	@Test
	void 값이_바뀌면_직전_조회와의_중간_시각을_기준으로_계산한다() {
		estimator.estimate(NODE, List.of(bus("113", 600, 5)), at(0));

		Estimate estimate = estimator.estimate(NODE, List.of(bus("113", 500, 4)), at(60));

		assertThat(estimate.changeAt()).isEqualTo(at(30));
		assertThat(estimate.arrivals()).extracting(ArrivalInfo::expireAt).containsExactly(millis(30 + 500));
		assertThat(estimate.changes()).extracting(ArrivalEstimator.Change::type).containsExactly(ChangeType.UPDATED);
	}

	@Test
	void 남은_정류장_2개_이하에서_사라지면_도착으로_보고_제거한다() {
		estimator.estimate(NODE, List.of(bus("113", 90, 2)), at(0));

		Estimate estimate = estimator.estimate(NODE, List.of(), at(60));

		assertThat(estimate.arrivals()).isEmpty();
		assertThat(estimate.changes()).extracting(ArrivalEstimator.Change::type).containsExactly(ChangeType.ARRIVED);
	}

	@Test
	void 정류장이_많이_남았는데_사라지면_한_번은_예측을_유지하고_다음에도_없으면_제거한다() {
		estimator.estimate(NODE, List.of(bus("250", 700, 6)), at(0));

		Estimate missing = estimator.estimate(NODE, List.of(), at(60));
		assertThat(missing.arrivals()).extracting(ArrivalInfo::expireAt).containsExactly(millis(700));
		assertThat(missing.changes()).extracting(ArrivalEstimator.Change::type).containsExactly(ChangeType.MISSING);

		Estimate dropped = estimator.estimate(NODE, List.of(), at(120));
		assertThat(dropped.arrivals()).isEmpty();
		assertThat(dropped.changes()).extracting(ArrivalEstimator.Change::type).containsExactly(ChangeType.DROPPED);
	}

	@Test
	void 사라졌던_버스가_다시_나타나면_같은_버스로_이어서_추적한다() {
		estimator.estimate(NODE, List.of(bus("250", 700, 6)), at(0));
		estimator.estimate(NODE, List.of(), at(60));

		Estimate estimate = estimator.estimate(NODE, List.of(bus("250", 700, 6)), at(120));

		assertThat(estimate.arrivals()).extracting(ArrivalInfo::expireAt).containsExactly(millis(700));
		assertThat(estimate.changed()).isFalse();
	}

	@Test
	void 앞차가_도착하고_뒤에_새_버스가_나타나도_버스를_섞지_않는다() {
		estimator.estimate(NODE, List.of(bus("113", 100, 2), bus("113", 1400, 12)), at(0));

		Estimate estimate = estimator.estimate(NODE, List.of(bus("113", 1300, 11), bus("113", 2000, 16)), at(60));

		assertThat(estimate.changes()).extracting(ArrivalEstimator.Change::type)
			.containsExactlyInAnyOrder(ChangeType.ARRIVED, ChangeType.UPDATED, ChangeType.APPEARED);
		assertThat(estimate.arrivals()).extracting(ArrivalInfo::expireAt)
			.containsExactly(millis(30 + 1300), millis(30 + 2000));
	}

	@Test
	void 노선마다_따로_변경을_감지한다() {
		estimator.estimate(NODE, List.of(bus("113", 600, 5), bus("250", 900, 8)), at(0));

		Estimate estimate = estimator.estimate(NODE, List.of(bus("113", 500, 4), bus("250", 900, 8)), at(60));

		assertThat(estimate.changes()).extracting(ArrivalEstimator.Change::routeNo).containsExactly("113");
		assertThat(estimate.arrivals()).extracting(ArrivalInfo::expireAt).containsExactly(millis(530), millis(900));
	}

	@Test
	void 조회_간격이_너무_벌어지면_이전_상태를_버리고_조회_시각을_기준으로_계산한다() {
		estimator.estimate(NODE, List.of(bus("113", 900, 8)), at(0));

		Estimate estimate = estimator.estimate(NODE, List.of(bus("113", 300, 3)), at(600));

		assertThat(estimate.previousPollAt()).isNull();
		assertThat(estimate.arrivals()).extracting(ArrivalInfo::expireAt).containsExactly(millis(600 + 300));
	}

	@Test
	void 추적하지_않는_정류장은_조회_시각을_기준으로_계산한다() {
		ArrivalItem other = new ArrivalItem("OTHER", "기타", "R113", "113", "농어촌버스", "일반차량", 300, 3);
		estimator.estimate("OTHER", List.of(other), at(0));

		Estimate estimate = estimator.estimate("OTHER", List.of(other), at(60));

		assertThat(estimate.arrivals()).extracting(ArrivalInfo::expireAt).containsExactly(millis(60 + 300));
	}
}
