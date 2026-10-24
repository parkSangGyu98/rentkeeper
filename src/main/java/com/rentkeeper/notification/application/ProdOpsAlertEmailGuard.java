package com.rentkeeper.notification.application;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 운영(prod)에서는 운영 알림 메일 주소가 있어야 기동한다 (M6 결정 8). 배치 실패·알림 발송 실패를 메일로 바로 알기 위해서다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
class ProdOpsAlertEmailGuard {

	ProdOpsAlertEmailGuard(NotificationProperties properties) {
		if (properties.opsAlertEmail() == null) {
			throw new IllegalStateException("운영(prod)에는 운영 알림 메일 주소(OPS_ALERT_EMAIL)가 필요합니다 (M6 결정 8).");
		}
	}

}
