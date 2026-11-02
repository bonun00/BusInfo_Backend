package bonun.bustime_backend.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import bonun.bustime_backend.dto.ArrivalInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 정류장별 도착 정보 Redis 캐시. Redis 오류는 예외 대신 miss/실패로 돌려준다.
@Component
@RequiredArgsConstructor
@Slf4j
public class ArrivalCacheStore {

	private static final String KEY_PREFIX = "arrivalInfo:";

	private final RedisTemplate<String, Object> redisTemplate;

	@SuppressWarnings("unchecked")
	public Optional<List<ArrivalInfo>> get(String nodeId) {
		try {
			Object cached = redisTemplate.opsForValue().get(key(nodeId));
			if (cached instanceof List<?> list) {
				return Optional.of((List<ArrivalInfo>) list);
			}
		} catch (RuntimeException e) {
			// Redis 장애나 역직렬화 실패는 캐시 miss로 처리
			log.warn("Redis 조회 실패 - key={}: {}", key(nodeId), e.toString());
		}
		return Optional.empty();
	}

	public boolean put(String nodeId, List<ArrivalInfo> arrivals, Duration ttl) {
		try {
			// 직렬화 시 구현 클래스명이 함께 저장되므로, 역직렬화 가능한 ArrayList로 저장한다
			redisTemplate.opsForValue().set(key(nodeId), new ArrayList<>(arrivals), ttl);
			return true;
		} catch (RuntimeException e) {
			log.warn("Redis 저장 실패 - key={}: {}", key(nodeId), e.toString());
			return false;
		}
	}

	private static String key(String nodeId) {
		return KEY_PREFIX + nodeId;
	}
}
