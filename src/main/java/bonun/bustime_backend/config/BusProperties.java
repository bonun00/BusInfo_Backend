package bonun.bustime_backend.config;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "bus")
public record BusProperties(
	@DefaultValue Scheduler scheduler,
	@DefaultValue List<Stop> stops
) {

	public record Scheduler(
		@DefaultValue("false") boolean enabled,
		@DefaultValue("60s") Duration pollInterval,
		@DefaultValue("6") int maxCallsPerTick,
		@DefaultValue("Asia/Seoul") String zone,
		@DefaultValue("06:00") String activeFrom,
		@DefaultValue("23:00") String activeTo,
		@DefaultValue("400000") long dailyCallLimit,
		@DefaultValue("2") int arrivalStationCount,
		@DefaultValue("300s") Duration cacheTtl
	) {

		public ZoneId zoneId() {
			return ZoneId.of(zone);
		}

		// enabled는 실행 중에 확인한다. @ConditionalOnProperty는 AOT 빌드 시점에 고정되기 때문
		public boolean isActive(Clock clock) {
			LocalTime now = LocalTime.now(clock.withZone(zoneId()));
			return enabled && !now.isBefore(LocalTime.parse(activeFrom)) && now.isBefore(LocalTime.parse(activeTo));
		}

		// 이보다 오래 조회가 끊기면 이전 상태를 버린다 (야간 중지, 장애 등)
		public Duration maxPollGap() {
			return pollInterval.multipliedBy(2).plusSeconds(30);
		}
	}

	public record Stop(String name, String nodeId) {
	}

	// 같은 ID가 여러 이름으로 등록돼 있어도 한 번만 조회한다
	public Set<String> nodeIds() {
		Set<String> ids = new LinkedHashSet<>();
		for (Stop stop : stops) {
			ids.add(stop.nodeId().trim());
		}
		return ids;
	}
}
