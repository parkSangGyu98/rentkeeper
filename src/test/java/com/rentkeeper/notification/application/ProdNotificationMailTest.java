package com.rentkeeper.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.rentkeeper.common.mail.EmailMessage;
import com.rentkeeper.common.mail.EmailSender;
import com.rentkeeper.notification.domain.NotificationChannel;
import com.rentkeeper.notification.domain.NotificationType;

/**
 * 운영에서 알림 메일 채널은 운영 메일 발송(SMTP)으로 나가고, 운영 알림 주소가 없으면 기동하지 않는다 (M6 커밋 2, 결정
 * 5·8). 세입자 휴대폰 채널은 여전히 기본 꺼짐이라 발송기가 없어도 된다.
 */
class ProdNotificationMailTest {

	private final List<EmailMessage> mails = new ArrayList<>();

	private final EmailSender emailSender = mails::add;

	@Test
	void emailChannelGoesThroughTheMailSender() {
		EmailNotificationSender sender = new EmailNotificationSender(emailSender);

		sender.send(new OutboundNotification(7, NotificationType.OVERDUE_DIGEST, NotificationChannel.EMAIL,
				"LANDLORD:1", "kim@example.com", "제목", "본문"));

		assertThat(sender.supports(NotificationChannel.EMAIL)).isTrue();
		assertThat(sender.supports(NotificationChannel.TENANT_PHONE)).isFalse();
		assertThat(mails).singleElement().satisfies(m -> {
			assertThat(m.to()).isEqualTo("kim@example.com");
			assertThat(m.subject()).isEqualTo("제목");
			assertThat(m.body()).isEqualTo("본문");
		});
	}

	@Test
	void prodStartsWithTheMailSenderAndAnOpsAddress() {
		prod("ops@example.com").run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void prodNeedsAnOpsAlertAddress() {
		prod(null).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("OPS_ALERT_EMAIL");
		});
	}

	private ApplicationContextRunner prod(String opsAlertEmail) {
		return new ApplicationContextRunner()
				.withBean(NotificationProperties.class,
						() -> new NotificationProperties(null, null, null, opsAlertEmail))
				.withBean(EmailSender.class, () -> emailSender)
				.withUserConfiguration(EmailNotificationSender.class, NotificationChannels.class,
						NotificationSenders.class, ProdNotificationSenderGuard.class, ProdOpsAlertEmailGuard.class)
				.withPropertyValues("spring.profiles.active=prod");
	}

}
