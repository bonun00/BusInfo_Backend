package bonun.bustime_backend.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import bonun.bustime_backend.config.BusProperties;
import lombok.extern.slf4j.Slf4j;

// 공공 API 일일 호출 수. Redis에 기록하고, Redis가 안 되면 이 인스턴스의 메모리 값으로 대신한다.
@Component
@Slf4j
public class DailyCallCounter {

	private static final String KEY_PREFIX = "publicApi:calls:";

	private final RedisTemplate<String, Object> redisTemplate;
	private final Clock clock;
	private final ZoneId zone;

	private String day = "";
	private long localCount;
	private long knownCount;

	public DailyCallCounter(RedisTemplate<String, Object> redisTemplate, Clock clock, BusProperties properties) {
		this.redisTemplate = redisTemplate;
		this.clock = clock;
		this.zone = properties.scheduler().zoneId();
	}

	public long increment() {
		String today;
		long local;
		synchronized (this) {
			today = rollOver();
			local = ++localCount;
			knownCount = Math.max(knownCount, local);
		}
		try {
			String key = KEY_PREFIX + today;
			Long count = redisTemplate.opsForValue().increment(key);
			if (count != null && count == 1L) {
				redisTemplate.expire(key, Duration.ofDays(2));
			}
			if (count != null) {
				synchronized (this) {
					if (today.equals(day)) {
						knownCount = Math.max(knownCount, count);
					}
				}
				return count;
			}
		} catch (RuntimeException e) {
			log.debug("호출 수 기록 실패: {}", e.toString());
		}
		return local;
	}

	public synchronized long todayCount() {
		rollOver();
		return knownCount;
	}

	public boolean limitReached(long limit) {
		return todayCount() >= limit;
	}

	private String rollOver() {
		String today = LocalDate.now(clock.withZone(zone)).format(DateTimeFormatter.BASIC_ISO_DATE);
		if (!today.equals(day)) {
			day = today;
			localCount = 0;
			knownCount = 0;
		}
		return today;
	}
}
