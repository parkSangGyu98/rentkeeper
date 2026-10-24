package com.rentkeeper.notification.application;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.rentkeeper.notification.domain.NotificationChannel;

/**
 * 운영(prod)에서 켜진 채널에 실제 발송기가 없거나 로그 기록용이 쓰이면 앱을 띄우지 않는다 (M5 결정 6·11, ADR-010과 같은
 * 이유). 세입자 휴대폰 채널은 기본 꺼짐이라, 켜지 않는 한 문자 발송기가 없어도 이 검사에 걸리지 않는다(세입자 알림은 SKIPPED).
 * 메일은 늘 켜져 있으므로 실제 메일 발송기를 붙이기 전(M6)에는 prod가 뜨지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
class ProdNotificationSenderGuard {

	ProdNotificationSenderGuard(NotificationChannels channels, NotificationSenders senders) {
		for (NotificationChannel channel : NotificationChannel.values()) {
			if (!channels.enabled(channel)) {
				continue;
			}
			NotificationSender sender = senders.forChannel(channel).orElseThrow(() -> new IllegalStateException(
					"운영(prod)에서 켜진 알림 채널 " + channel + "의 발송기(NotificationSender)가 없습니다 (M5 결정 11)."));
			if (sender instanceof LoggingNotificationSender) {
				throw new IllegalStateException(
						"운영(prod)에서 알림 채널 " + channel + "에 개발용 LoggingNotificationSender를 쓸 수 없습니다 (M5 결정 11).");
			}
		}
	}

}
