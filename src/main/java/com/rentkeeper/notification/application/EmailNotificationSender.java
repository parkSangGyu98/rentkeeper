package com.rentkeeper.notification.application;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.rentkeeper.common.mail.EmailMessage;
import com.rentkeeper.common.mail.EmailSender;
import com.rentkeeper.notification.domain.NotificationChannel;

/**
 * 알림 메일 채널의 실제 발송기 (M6 결정 5). 임대인 요약 메일과 운영 알림 메일을 운영 메일 발송(SMTP)으로 보낸다. 세입자
 * 휴대폰 채널은 맡지 않는다(문자 대행사는 실서비스 전환 때). local에서는 로그 기록용 발송기가 계속 맡는다.
 */
@Component
@Profile("prod")
class EmailNotificationSender implements NotificationSender {

	private final EmailSender emailSender;

	EmailNotificationSender(EmailSender emailSender) {
		this.emailSender = emailSender;
	}

	@Override
	public boolean supports(NotificationChannel channel) {
		return channel == NotificationChannel.EMAIL;
	}

	@Override
	public void send(OutboundNotification message) {
		emailSender.send(new EmailMessage(message.to(), message.subject(), message.body()));
	}

}
