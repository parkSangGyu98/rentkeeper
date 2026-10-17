package com.rentkeeper.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** local 전용 "오늘 바꾸기" (M3 결정 19): local에서만 동작하고, 그 밖에서 설정돼 있으면 앱이 뜨지 않는다 */
class ClockConfigTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(ClockConfig.class);

	@Test
	void localProfileShiftsTodayButKeepsSeoulZone() {
		runner.withPropertyValues("spring.profiles.active=local", "app.dev.today=2026-12-01").run(context -> {
			Clock clock = context.getBean(Clock.class);
			assertThat(LocalDate.now(clock)).isEqualTo(LocalDate.of(2026, 12, 1));
			assertThat(clock.getZone()).isEqualTo(ClockConfig.SEOUL);
		});
	}

	@Test
	void withoutValueUsesSystemClock() {
		runner.withPropertyValues("spring.profiles.active=local", "app.dev.today=").run(context -> {
			Clock clock = context.getBean(Clock.class);
			assertThat(clock).isEqualTo(Clock.system(ClockConfig.SEOUL));
		});
	}

	@Test
	void prodFailsToStartWhenValueIsSet() {
		runner.withPropertyValues("spring.profiles.active=prod", "app.dev.today=2026-12-01")
				.run(context -> assertThat(context).hasFailed().getFailure().rootCause()
						.hasMessageContaining("app.dev.today"));
	}

	@Test
	void prodTogetherWithLocalAlsoFails() {
		runner.withPropertyValues("spring.profiles.active=local,prod", "app.dev.today=2026-12-01")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void otherProfilesFail() {
		runner.withPropertyValues("spring.profiles.active=staging", "app.dev.today=2026-12-01")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void prodWithoutValueStarts() {
		runner.withPropertyValues("spring.profiles.active=prod")
				.run(context -> assertThat(context).hasNotFailed().hasSingleBean(Clock.class));
	}

}
