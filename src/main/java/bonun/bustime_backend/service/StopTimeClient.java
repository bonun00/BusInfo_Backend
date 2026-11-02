package bonun.bustime_backend.service;

import bonun.bustime_backend.dto.ArrivalInfo;
import bonun.bustime_backend.dto.ArrivalItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Service
@Slf4j
public class StopTimeClient {

    private final ArrivalApiClient apiClient;
    private final ArrivalCacheStore cacheStore;

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
            List<ArrivalItem> items = apiClient.fetch(nodeId);
            long now = System.currentTimeMillis();

            List<ArrivalInfo> result = new ArrayList<>();
            int minArrTimeSec = Integer.MAX_VALUE;
            int minPrevStationCnt = Integer.MAX_VALUE;
            for (ArrivalItem item : items) {
                if (item.arrTime() > 0) {
                    minArrTimeSec = Math.min(minArrTimeSec, item.arrTime());
                }
                if (item.arrPrevStationCnt() > 0) {
                    minPrevStationCnt = Math.min(minPrevStationCnt, item.arrPrevStationCnt());
                }
                result.add(item.toArrivalInfo(now + Math.max(0, item.arrTime()) * 1000L));
            }

            Duration ttl = computeTtlSeconds(minArrTimeSec);
            log.info("API 조회 후 Redis에 저장: arrivalInfo:{} (ttl={}s, minArrTime={}s, minPrevStationCnt={})",
                nodeId, ttl.getSeconds(), minArrTimeSec, minPrevStationCnt);
            cacheStore.put(nodeId, result, ttl);

            return result;

        } catch (Exception e) {
            log.error("API 호출 실패 - nodeId={}", nodeId, e);
            return Collections.emptyList();
        }
    }
}
