package com.rentkeeper.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.rentkeeper.notification.domain.NotificationChannel;

/**
 * 운영(prod)에서는 켜진 채널마다 실제 발송기가 있어야 한다 (M5 결정 6·11). 세입자 휴대폰 채널은 기본 꺼짐이라 문자 발송기가
 * 없어도 걸리지 않는다. 로그 기록용 발송기는 local 전용이다.
 */
class NotificationSenderProfileTest {

	private static final NotificationSender REAL_EMAIL = new NotificationSender() {
		@Override
		public boolean supports(NotificationChannel channel) {
			return channel == NotificationChannel.EMAIL;
		}

		@Override
		public void send(OutboundNotification message) {
		}
	};

	private static final NotificationSender REAL_PHONE = new NotificationSender() {
		@Override
		public boolean supports(NotificationChannel channel) {
			return channel == NotificationChannel.TENANT_PHONE;
		}

		@Override
		public void send(OutboundNotification message) {
		}
	};

	@Test
	void loggingSenderIsRegisteredOnlyInLocalProfile() {
		ApplicationContextRunner senderOnly = new ApplicationContextRunner()
				.withUserConfiguration(LoggingNotificationSender.class);
		senderOnly.withPropertyValues("spring.profiles.active=local")
				.run(context -> assertThat(context).hasSingleBean(LoggingNotificationSender.class));
		senderOnly.withPropertyValues("spring.profiles.active=prod")
				.run(context -> assertThat(context).doesNotHaveBean(LoggingNotificationSender.class));
	}

	@Test
	void prodFailsWithoutAnEmailSender() {
		prod(false).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("EMAIL");
		});
	}

	@Test
	void prodStartsWithAnEmailSenderWhileTheTenantPhoneIsOff() {
		prod(false).withBean("email", NotificationSender.class, () -> REAL_EMAIL)
				.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void prodRefusesTheLoggingSenderForATurnedOnTenantPhone() {
		prod(true).withBean("email", NotificationSender.class, () -> REAL_EMAIL)
				.withBean("logging", NotificationSender.class, LoggingNotificationSender::new).run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("TENANT_PHONE")
							.hasMessageContaining("LoggingNotificationSender");
				});
		prod(true).withBean("email", NotificationSender.class, () -> REAL_EMAIL)
				.withBean("phone", NotificationSender.class, () -> REAL_PHONE)
				.run(context -> assertThat(context).hasNotFailed());
	}

	private static ApplicationContextRunner prod(boolean tenantPhone) {
		return new ApplicationContextRunner()
				.withBean(NotificationProperties.class,
						() -> new NotificationProperties(null, null,
								new NotificationProperties.TenantPhone(tenantPhone), null))
				.withUserConfiguration(NotificationChannels.class, NotificationSenders.class,
						ProdNotificationSenderGuard.class)
				.withPropertyValues("spring.profiles.active=prod");
	}

}
