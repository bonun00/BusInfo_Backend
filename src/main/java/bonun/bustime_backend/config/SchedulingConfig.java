package bonun.bustime_backend.config;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import bonun.bustime_backend.service.ArrivalEstimator;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(BusProperties.class)
public class SchedulingConfig {

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	public ArrivalEstimator arrivalEstimator(BusProperties properties) {
		BusProperties.Scheduler scheduler = properties.scheduler();
		return new ArrivalEstimator(properties.nodeIds(), scheduler.arrivalStationCount(), scheduler.maxPollGap());
	}

	// 회차당 호출을 동시에 보내는 전용 스레드 풀. Tomcat 요청 스레드·스케줄러 스레드와 분리한다
	@Bean(destroyMethod = "shutdownNow")
	public ExecutorService arrivalRefreshExecutor(BusProperties properties) {
		AtomicInteger sequence = new AtomicInteger();
		return Executors.newFixedThreadPool(properties.scheduler().maxCallsPerTick(), runnable -> {
			Thread thread = new Thread(runnable, "arrival-refresh-" + sequence.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
	}
}
