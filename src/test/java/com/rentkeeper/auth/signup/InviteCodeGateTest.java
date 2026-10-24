package com.rentkeeper.auth.signup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.rentkeeper.common.ratelimit.RateLimitRule;
import com.rentkeeper.common.ratelimit.RateLimiter;

/**
 * 초대 코드 비교 (M6 결정 3): 앞뒤 공백 무시, 길이가 달라도 같은 방식으로 비교, 시도마다 횟수를 센다, 운영은 16자 이상 필수
 */
class InviteCodeGateTest {

	private final RateLimiter rateLimiter = mock(RateLimiter.class);

	@Test
	void comparesWholeCodesAndCountsEveryAttempt() {
		InviteCodeGate gate = new InviteCodeGate("open-sesame-2026", rateLimiter);

		assertThat(gate.required()).isTrue();
		assertThat(gate.accepts(" open-sesame-2026 ", "1.2.3.4")).isTrue();
		assertThat(gate.accepts("open-sesame-202", "1.2.3.4")).isFalse();
		assertThat(gate.accepts("open-sesame-20266", "1.2.3.4")).isFalse();
		assertThat(gate.accepts(null, "1.2.3.4")).isFalse();
		verify(rateLimiter, times(4)).acquireOrThrow(RateLimitRule.INVITE_CODE_PER_IP, "1.2.3.4");
	}

	@Test
	void blankSettingMeansOpenSignup() {
		InviteCodeGate gate = new InviteCodeGate(" ", rateLimiter);

		assertThat(gate.required()).isFalse();
		assertThat(gate.accepts("", "1.2.3.4")).isTrue();
		verify(rateLimiter, never()).acquireOrThrow(any(), anyString());
	}

	@Test
	void prodNeedsALongInviteCode() {
		ApplicationContextRunner runner = new ApplicationContextRunner()
				.withUserConfiguration(ProdInviteCodeGuard.class).withPropertyValues("spring.profiles.active=prod");

		runner.run(context -> assertThat(context.getStartupFailure()).rootCause()
				.hasMessageContaining("APP_SIGNUP_INVITE_CODE"));
		runner.withPropertyValues("app.signup.invite-code=short").run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("app.signup.invite-code=0123456789abcdef")
				.run(context -> assertThat(context).hasNotFailed());
	}

}
