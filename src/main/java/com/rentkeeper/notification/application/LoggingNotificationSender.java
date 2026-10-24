package com.rentkeeper.notification.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.rentkeeper.notification.domain.NotificationChannel;

/**
 * 개발용: 알림을 보내지 않고 ID·종류·채널·받는 사람 ID만 로그에 남긴다 (M5 결정 11, 개발자 조건 1). 개발용 메일
 * 구현(LoggingEmailSender)과 달리 본문·번호·주소·링크는 찍지 않는다. local 프로필에서만 빈으로 등록되고, 운영에서는
 * {@link ProdNotificationSenderGuard}가 이 구현이 쓰이지 않는지 확인한다.
 */
@Component
@Profile("local")
public class LoggingNotificationSender implements NotificationSender {

	public static final String LOGGER_NAME = "rentkeeper.dev-notification";

	private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

	@Override
	public boolean supports(NotificationChannel channel) {
		return true;
	}

	@Override
	public void send(OutboundNotification message) {
		log.info("[개발용 알림 - 실제로 보내지 않음] id={} type={} channel={} recipient={}", message.notificationId(),
				message.type(), message.channel(), message.recipient());
	}

}
