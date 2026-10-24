package com.rentkeeper.notification.domain;

/** 보내지 않은 이유 (V19 ck_notification_skip_reason). 화면 문구는 이력 화면이 정한다 */
public enum SkipReason {

	/** 세입자 안내 문자 기능이 꺼져 있음 (서비스 설정, M5 결정 6) */
	CHANNEL_OFF,

	/** 임대인이 알림 설정에서 끔 (M5 결정 13) */
	LANDLORD_OFF,

	/** 세입자가 안내 문자를 그만 받기로 함 (TEN-6) */
	OPTED_OUT,

	/** 임대인이 [링크 닫기]로 이 계약의 알림을 멈춤 (M5 결정 8) */
	LINK_CLOSED_BY_LANDLORD,

	/** 계약이 끝나 링크를 쓸 수 없음 */
	LEASE_ENDED,

	/** 보낼 때 받을 월세 상태가 바뀜 */
	STALE,

	/** 같은 계약에 오늘 이미 보냄 (M5 결정 12) */
	DAILY_CAP,

	/** 임대인 이메일이 없거나 인증 전 */
	EMAIL_UNVERIFIED,

	/** 운영 알림 주소(OPS_ALERT_EMAIL)가 없음 */
	NO_ADDRESS

}
