package com.rentkeeper.notification.application;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.rentkeeper.notification.domain.Notification;
import com.rentkeeper.notification.domain.NotificationType;
import com.rentkeeper.notification.domain.SkipReason;

/** 운영 알림 메일 본문 (M5 결정 16). 코드만 넣는다 */
@Component
class OpsNotificationComposer implements NotificationComposer {

	private final NotificationProperties properties;

	OpsNotificationComposer(NotificationProperties properties) {
		this.properties = properties;
	}

	@Override
	public boolean supports(NotificationType type) {
		return type == NotificationType.OPS_ALERT;
	}

	@Override
	public Composition compose(Notification notification, Instant now) {
		if (properties.opsAlertEmail() == null) {
			return new Composition.Skip(SkipReason.NO_ADDRESS);
		}
		NotificationTemplates.Mail mail = NotificationTemplates.opsAlert(notification.getOpsCode());
		return new Composition.Send(properties.opsAlertEmail(), mail.subject(), mail.body());
	}

}
