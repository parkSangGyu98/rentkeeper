package com.rentkeeper.notification.application;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.rentkeeper.notification.domain.NotificationChannel;

/** 채널에 맞는 발송기 고르기. 실제 발송기가 있으면 로그 기록용보다 먼저 쓴다 */
@Component
public class NotificationSenders {

	private final List<NotificationSender> senders;

	NotificationSenders(List<NotificationSender> senders) {
		this.senders = senders;
	}

	public Optional<NotificationSender> forChannel(NotificationChannel channel) {
		return senders.stream().filter(sender -> sender.supports(channel)).min((a, b) -> Boolean
				.compare(a instanceof LoggingNotificationSender, b instanceof LoggingNotificationSender));
	}

}
