package com.rentkeeper.auth.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 파기 배치 수동 실행(브라우저 확인용)은 local 프로필 + 명시한 설정일 때만 있다. prod에서는 호출할 방법이 없다 (M1-c 보완
 * b).
 */
class LocalPurgeRunnerTests {

	private final WithdrawnAccountPurger purger = mock(WithdrawnAccountPurger.class);

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withBean(WithdrawnAccountPurger.class, () -> purger).withUserConfiguration(LocalPurgeRunner.class);

	@Test
	void runsOnceOnStartupOnlyInLocalWithProperty() {
		runner.withPropertyValues("spring.profiles.active=local", "app.purge.run-on-startup=true").run(context -> {
			assertThat(context).hasSingleBean(LocalPurgeRunner.class);
			context.getBean(LocalPurgeRunner.class).run(null);
			verify(purger).run();
		});
	}

	@Test
	void absentInLocalWithoutProperty() {
		runner.withPropertyValues("spring.profiles.active=local")
				.run(context -> assertThat(context).doesNotHaveBean(LocalPurgeRunner.class));
	}

	@Test
	void absentInProdEvenWithProperty() {
		runner.withPropertyValues("spring.profiles.active=prod", "app.purge.run-on-startup=true").run(context -> {
			assertThat(context).doesNotHaveBean(LocalPurgeRunner.class);
			verify(purger, never()).run();
		});
	}

}
