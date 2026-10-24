package com.rentkeeper.notification.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 알림 설정 (M5 결정 3·6·16).
 *
 * @param sendWindowStartHour
 *            발송 시작 시각 (서울, 시). 기본 9
 * @param sendWindowEndHour
 *            발송 끝 시각 (서울, 시). 이 시각부터는 보내지 않는다. 기본 20
 * @param tenantPhone
 *            세입자 휴대폰 채널
 * @param opsAlertEmail
 *            운영 알림을 받을 주소 (환경변수 OPS_ALERT_EMAIL). 비면 운영 알림은 SKIPPED(NO_ADDRESS)
 */
@ConfigurationProperties("app.notification")
public record NotificationProperties(Integer sendWindowStartHour, Integer sendWindowEndHour, TenantPhone tenantPhone,
		String opsAlertEmail) {

	public NotificationProperties {
		sendWindowStartHour = sendWindowStartHour == null ? 9 : sendWindowStartHour;
		sendWindowEndHour = sendWindowEndHour == null ? 20 : sendWindowEndHour;
		if (sendWindowStartHour < 0 || sendWindowEndHour > 24 || sendWindowStartHour >= sendWindowEndHour) {
			throw new IllegalStateException("app.notification.send-window-*-hour는 0 <= 시작 < 끝 <= 24여야 합니다");
		}
		tenantPhone = tenantPhone == null ? new TenantPhone(false) : tenantPhone;
		opsAlertEmail = opsAlertEmail == null || opsAlertEmail.isBlank() ? null : opsAlertEmail.trim();
	}

	/**
	 * @param enabled
	 *            세입자에게 안내 문자를 보낼지 (환경변수 APP_NOTIFICATION_TENANT_PHONE_ENABLED, 기본
	 *            false). 실제 문자 발송기를 연결하기 전에는 켜지 않는다. 꺼져 있으면 세입자 알림은 만들 때
	 *            SKIPPED(CHANNEL_OFF)다
	 */
	public record TenantPhone(boolean enabled) {
	}

}
