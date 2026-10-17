package com.rentkeeper.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 청구 배치 수동 실행(브라우저 확인용)은 local 프로필 + 명시한 설정일 때만 있다. prod에서는 호출할 방법이 없다 */
class LocalBillingRunnerTests {

	private final ChargeGenerator generator = mock(ChargeGenerator.class);

	private final ChargeOverdueMarker overdueMarker = mock(ChargeOverdueMarker.class);

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withBean(ChargeGenerator.class, () -> generator).withBean(ChargeOverdueMarker.class, () -> overdueMarker)
			.withUserConfiguration(LocalBillingRunner.class);

	@Test
	void runsBothJobsOnceOnStartupOnlyInLocalWithProperty() {
		runner.withPropertyValues("spring.profiles.active=local", "app.billing.run-on-startup=true").run(context -> {
			assertThat(context).hasSingleBean(LocalBillingRunner.class);
			context.getBean(LocalBillingRunner.class).run(null);
			verify(generator).run();
			verify(overdueMarker).run();
		});
	}

	@Test
	void absentInLocalWithoutProperty() {
		runner.withPropertyValues("spring.profiles.active=local")
				.run(context -> assertThat(context).doesNotHaveBean(LocalBillingRunner.class));
	}

	@Test
	void absentInProdEvenWithProperty() {
		runner.withPropertyValues("spring.profiles.active=prod", "app.billing.run-on-startup=true").run(context -> {
			assertThat(context).doesNotHaveBean(LocalBillingRunner.class);
			verify(generator, never()).run();
		});
	}

}
