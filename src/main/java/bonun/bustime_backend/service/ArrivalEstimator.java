package bonun.bustime_backend.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import bonun.bustime_backend.dto.ArrivalInfo;
import bonun.bustime_backend.dto.ArrivalItem;

/**
 * 공공 API 값이 바뀐 시각을 기준으로 도착 예정 시각(expireAt)을 계산한다.
 *
 * TAGO는 arrtime을 평균 2분가량 그대로 두다가 한 번에 바꾸기 때문에, 조회 시각 + arrtime으로 계산하면
 * 다시 조회할 때마다 도착 예정 시각이 뒤로 밀린다. 그래서 정류장별 직전 조회 결과를 보관하고,
 * 값이 바뀌었을 때만 "직전 조회와 이번 조회의 중간 시각 + arrtime"으로 다시 계산한다.
 */
public class ArrivalEstimator {

	public enum ChangeType {
		APPEARED, UPDATED, ARRIVED, MISSING, DROPPED
	}

	public record Change(ChangeType type, String routeNo, int arrTime, int arrPrevStationCnt) {
	}

	/**
	 * @param previousPollAt 직전 조회 시각 (연속된 조회가 아니면 null)
	 * @param changeAt       이번에 바뀐 값의 기준 시각
	 */
	public record Estimate(List<ArrivalInfo> arrivals, List<Change> changes, Instant previousPollAt, Instant changeAt) {

		public boolean changed() {
			return !changes.isEmpty();
		}
	}

	private record TrackedBus(ArrivalItem item, long expireAt, int missedPolls) {
	}

	private static final class StopState {
		private Instant lastPolledAt;
		private Map<String, List<TrackedBus>> byRoute = Map.of();
	}

	// 같은 버스라면 남은 정류장 수는 줄거나 그대로이고, 늘지 않는다
	private static final Comparator<ArrivalItem> BY_DISTANCE = Comparator
		.comparingInt(ArrivalItem::arrPrevStationCnt)
		.thenComparingInt(ArrivalItem::arrTime);

	private final Set<String> trackedNodeIds;
	private final int arrivalStationCount;
	private final Duration maxPollGap;
	private final Map<String, StopState> states = new ConcurrentHashMap<>();

	/**
	 * @param trackedNodeIds      상태를 보관할 정류장 (그 외 정류장은 조회 시각 기준으로 계산)
	 * @param arrivalStationCount 이 정류장 수 이하에서 응답에서 사라지면 도착으로 판단
	 * @param maxPollGap          직전 조회와 이 시간 넘게 벌어지면 이전 상태를 버리고 새로 시작
	 */
	public ArrivalEstimator(Set<String> trackedNodeIds, int arrivalStationCount, Duration maxPollGap) {
		this.trackedNodeIds = Set.copyOf(trackedNodeIds);
		this.arrivalStationCount = arrivalStationCount;
		this.maxPollGap = maxPollGap;
	}

	public Estimate estimate(String nodeId, List<ArrivalItem> items, Instant now) {
		if (!trackedNodeIds.contains(nodeId)) {
			List<ArrivalInfo> arrivals = new ArrayList<>();
			for (ArrivalItem item : items) {
				arrivals.add(item.toArrivalInfo(expireAt(now, item)));
			}
			return new Estimate(arrivals, List.of(), null, now);
		}

		StopState state = states.computeIfAbsent(nodeId, id -> new StopState());
		synchronized (state) {
			boolean continuous = state.lastPolledAt != null
				&& !now.isBefore(state.lastPolledAt)
				&& Duration.between(state.lastPolledAt, now).compareTo(maxPollGap) <= 0;

			Instant previousPollAt = continuous ? state.lastPolledAt : null;
			// 값은 직전 조회와 이번 조회 사이 어딘가에서 바뀌었다 → 중간 시각으로 추정 (오차는 최대 조회 간격의 절반)
			Instant changeAt = continuous ? midpoint(previousPollAt, now) : now;
			Map<String, List<TrackedBus>> previous = continuous ? state.byRoute : Map.of();

			Map<String, List<ArrivalItem>> current = new LinkedHashMap<>();
			for (ArrivalItem item : items) {
				current.computeIfAbsent(item.routeId(), route -> new ArrayList<>()).add(item);
			}

			Set<String> routes = new LinkedHashSet<>(previous.keySet());
			routes.addAll(current.keySet());

			List<Change> changes = new ArrayList<>();
			Map<String, List<TrackedBus>> next = new LinkedHashMap<>();
			for (String route : routes) {
				List<ArrivalItem> cur = new ArrayList<>(current.getOrDefault(route, List.of()));
				cur.sort(BY_DISTANCE);
				List<TrackedBus> matched = match(previous.getOrDefault(route, List.of()), cur, changeAt, changes);
				if (!matched.isEmpty()) {
					next.put(route, matched);
				}
			}

			state.byRoute = next;
			state.lastPolledAt = now;

			List<ArrivalInfo> arrivals = new ArrayList<>();
			next.values().forEach(buses -> buses.forEach(bus -> arrivals.add(bus.item().toArrivalInfo(bus.expireAt()))));
			arrivals.sort(Comparator.comparingLong(ArrivalInfo::expireAt));
			return new Estimate(arrivals, changes, previousPollAt, changeAt);
		}
	}

	// 직전 버스 목록(prev)과 이번 응답(cur)을 가까운 순서대로 맞춰 본다
	private List<TrackedBus> match(List<TrackedBus> prev, List<ArrivalItem> cur, Instant changeAt,
		List<Change> changes) {
		List<TrackedBus> next = new ArrayList<>();
		int i = 0;
		int j = 0;
		while (i < cur.size()) {
			ArrivalItem item = cur.get(i);
			if (j < prev.size()) {
				TrackedBus bus = prev.get(j);
				if (item.arrPrevStationCnt() <= bus.item().arrPrevStationCnt()) {
					if (item.arrTime() == bus.item().arrTime()
						&& item.arrPrevStationCnt() == bus.item().arrPrevStationCnt()) {
						// 값이 그대로면 기존 도착 예정 시각 유지
						next.add(new TrackedBus(item, bus.expireAt(), 0));
					} else {
						next.add(new TrackedBus(item, expireAt(changeAt, item), 0));
						changes.add(change(ChangeType.UPDATED, item));
					}
					i++;
					j++;
					continue;
				}
				// 이번 응답의 버스가 직전 버스보다 멀다 → 직전 버스는 이번 응답에서 사라졌다
				gone(bus, next, changes);
				j++;
				continue;
			}
			next.add(new TrackedBus(item, expireAt(changeAt, item), 0));
			changes.add(change(ChangeType.APPEARED, item));
			i++;
		}
		while (j < prev.size()) {
			gone(prev.get(j++), next, changes);
		}
		next.sort(Comparator.comparing(TrackedBus::item, BY_DISTANCE));
		return next;
	}

	private void gone(TrackedBus bus, List<TrackedBus> next, List<Change> changes) {
		if (bus.item().arrPrevStationCnt() <= arrivalStationCount) {
			changes.add(change(ChangeType.ARRIVED, bus.item()));
		} else if (bus.missedPolls() >= 1) {
			changes.add(change(ChangeType.DROPPED, bus.item()));
		} else {
			// 정류장이 많이 남았는데 사라지면 데이터 누락으로 보고 한 번은 기존 예측을 유지한다
			next.add(new TrackedBus(bus.item(), bus.expireAt(), bus.missedPolls() + 1));
			changes.add(change(ChangeType.MISSING, bus.item()));
		}
	}

	private static Change change(ChangeType type, ArrivalItem item) {
		return new Change(type, item.routeNo(), item.arrTime(), item.arrPrevStationCnt());
	}

	private static long expireAt(Instant base, ArrivalItem item) {
		return base.toEpochMilli() + Math.max(0, item.arrTime()) * 1000L;
	}

	private static Instant midpoint(Instant from, Instant to) {
		return from.plusMillis(Duration.between(from, to).toMillis() / 2);
	}
}
