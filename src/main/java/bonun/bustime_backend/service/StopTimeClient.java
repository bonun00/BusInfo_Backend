package bonun.bustime_backend.service;

import bonun.bustime_backend.alert.RefreshHealthMonitor;
import bonun.bustime_backend.config.BusProperties;
import bonun.bustime_backend.dto.ArrivalInfo;
import bonun.bustime_backend.dto.ArrivalItem;
import bonun.bustime_backend.service.ArrivalEstimator.Estimate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
@Service
@Slf4j
public class StopTimeClient {

    public enum RefreshResult {
        CHANGED, UNCHANGED, FAILED
    }

    private final ArrivalApiClient apiClient;
    private final ArrivalCacheStore cacheStore;
    private final ArrivalEstimator estimator;
    private final BusProperties properties;
    private final Clock clock;
    private final RefreshHealthMonitor healthMonitor;

    // 정류장별 마지막 Redis 저장 시각 (값이 그대로여도 TTL 절반이 지나면 다시 저장하기 위해)
    private final Map<String, Instant> lastWrittenAt = new ConcurrentHashMap<>();

    static Duration computeTtlSeconds(int minArrTimeSec) {

        if (minArrTimeSec <= 0 || minArrTimeSec == Integer.MAX_VALUE) {
            return Duration.ofSeconds(60);
        }

        int ttl;
        if (minArrTimeSec <= 60) {
            ttl = 10;
        } else if (minArrTimeSec <= 180) {
            ttl = 30;
        } else if (minArrTimeSec <= 600) {
            ttl = 60;
        } else {
            ttl = 180;
        }

        return Duration.ofSeconds(ttl);
    }

    public List<ArrivalInfo> getArrivalInfoByNodeId(String nodeId) {
        Optional<List<ArrivalInfo>> cached = cacheStore.get(nodeId);
        if (cached.isPresent()) {
            log.info("Redis 캐시에서 조회됨: arrivalInfo:{}", nodeId);
            return cached.get();
        }

        try {
            Instant now = clock.instant();
            List<ArrivalItem> items = apiClient.fetch(nodeId);
            Estimate estimate = estimator.estimate(nodeId, items, now);
            logChanges(nodeId, estimate);

            int minArrTimeSec = Integer.MAX_VALUE;
            int minPrevStationCnt = Integer.MAX_VALUE;
            for (ArrivalItem item : items) {
                if (item.arrTime() > 0) {
                    minArrTimeSec = Math.min(minArrTimeSec, item.arrTime());
                }
                if (item.arrPrevStationCnt() > 0) {
                    minPrevStationCnt = Math.min(minPrevStationCnt, item.arrPrevStationCnt());
                }
            }

            // 스케줄러가 갱신 중인 정류장은 스케줄러와 같은 TTL을 쓴다
            Duration ttl = isScheduled(nodeId) ? properties.scheduler().cacheTtl() : computeTtlSeconds(minArrTimeSec);
            log.info("API 조회 후 Redis에 저장: arrivalInfo:{} (ttl={}s, minArrTime={}s, minPrevStationCnt={})",
                nodeId, ttl.getSeconds(), minArrTimeSec, minPrevStationCnt);
            if (cacheStore.put(nodeId, estimate.arrivals(), ttl)) {
                lastWrittenAt.put(nodeId, now);
            }

            return estimate.arrivals();

        } catch (Exception e) {
            log.error("API 호출 실패 - nodeId={}", nodeId, e);
            return Collections.emptyList();
        }
    }

    // 스케줄러가 호출한다. 값이 바뀌었거나 마지막 저장 후 TTL 절반이 지났을 때만 Redis에 쓴다.
    public RefreshResult refreshScheduled(String nodeId) {
        try {
            Instant now = clock.instant();
            Estimate estimate = estimator.estimate(nodeId, apiClient.fetch(nodeId), now);
            healthMonitor.recordSuccess();
            logChanges(nodeId, estimate);

            Duration ttl = properties.scheduler().cacheTtl();
            Instant written = lastWrittenAt.get(nodeId);
            boolean expiring = written == null || Duration.between(written, now).compareTo(ttl.dividedBy(2)) >= 0;
            if ((estimate.changed() || expiring) && cacheStore.put(nodeId, estimate.arrivals(), ttl)) {
                lastWrittenAt.put(nodeId, now);
            }
            return estimate.changed() ? RefreshResult.CHANGED : RefreshResult.UNCHANGED;

        } catch (Exception e) {
            log.warn("스케줄러 갱신 실패 - nodeId={}: {}", nodeId, e.toString());
            healthMonitor.recordFailure(nodeId, e);
            return RefreshResult.FAILED;
        }
    }

    private boolean isScheduled(String nodeId) {
        return properties.scheduler().isActive(clock) && properties.nodeIds().contains(nodeId);
    }

    // 나중에 실제 오차를 다시 계산할 수 있도록 변경을 감지했을 때만 남긴다
    private void logChanges(String nodeId, Estimate estimate) {
        for (ArrivalEstimator.Change change : estimate.changes()) {
            log.info("도착 정보 변경: nodeId={} route={} type={} arrTime={}s stations={} prevPollAt={} changeAt={}",
                nodeId, change.routeNo(), change.type(), change.arrTime(), change.arrPrevStationCnt(),
                estimate.previousPollAt(), estimate.changeAt());
        }
    }
}
