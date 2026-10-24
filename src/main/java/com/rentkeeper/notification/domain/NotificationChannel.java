package com.rentkeeper.notification.domain;

/** 알림을 보내는 길 (M5 결정 4·6) */
public enum NotificationChannel {

	/** 세입자 휴대폰 (문자·알림톡). 아직 실제 발송기가 없고, 서비스 설정 기본값은 꺼짐이다 */
	TENANT_PHONE,

	/** 메일. 임대인은 인증한 주소로만, 운영 알림은 OPS_ALERT_EMAIL로 */
	EMAIL

}
