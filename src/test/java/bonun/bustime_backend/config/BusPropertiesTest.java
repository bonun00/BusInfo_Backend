package bonun.bustime_backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class BusPropertiesTest {

	@Test
	void application_yml의_스케줄러_설정과_정류장_목록을_바인딩한다() throws Exception {
		var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
		BusProperties properties = new Binder(ConfigurationPropertySources.from(sources))
			.bindOrCreate("bus", BusProperties.class);

		BusProperties.Scheduler scheduler = properties.scheduler();
		assertThat(scheduler.enabled()).isFalse();
		assertThat(scheduler.pollInterval()).isEqualTo(Duration.ofSeconds(60));
		assertThat(scheduler.maxCallsPerTick()).isEqualTo(6);
		assertThat(scheduler.cacheTtl()).isEqualTo(Duration.ofSeconds(300));
		assertThat(scheduler.arrivalStationCount()).isEqualTo(2);

		// 65개 중 이룡농협·이룡이 같은 ID라 64개
		assertThat(properties.stops()).hasSize(65);
		assertThat(properties.nodeIds()).hasSize(64).contains("TSB390000116", "TSB390000476");
	}
}
