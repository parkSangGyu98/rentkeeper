package com.rentkeeper.notification.domain;

/**
 * 알림 종류 (M5 결정 4). 받는 사람과 채널은 종류마다 정해져 있다. 운영 알림만 발송 시간(09:00~20:00) 제한이 없다.
 */
public enum NotificationType {

	/** 받을 월세 안내 (NOTI-1) */
	CHARGE_ISSUED(RecipientType.TENANT),

	/** 납부일 전날 안내 (NOTI-2, 전날 1회) */
	DUE_REMINDER(RecipientType.TENANT),

	/** 납부일 지남 안내 (NOTI-3, D+1·D+7) */
	OVERDUE_NOTICE(RecipientType.TENANT),

	/** 입금 신고 접수 요약 (NOTI-4, 임대인·시간당 1통) */
	TENANT_REPORT_DIGEST(RecipientType.LANDLORD),

	/** 납부일 지남 요약 (NOTI-4, 임대인·하루 1통) */
	OVERDUE_DIGEST(RecipientType.LANDLORD),

	/** 곧 끝나는 계약 요약 (NOTI-5, D-90·60·30, 임대인·하루 1통) */
	LEASE_END_DIGEST(RecipientType.LANDLORD),

	/** 운영 알림 (코드별 하루 1통) */
	OPS_ALERT(RecipientType.OPS);

	private final RecipientType recipientType;

	NotificationType(RecipientType recipientType) {
		this.recipientType = recipientType;
	}

	public RecipientType recipientType() {
		return recipientType;
	}

	public NotificationChannel channel() {
		return recipientType == RecipientType.TENANT ? NotificationChannel.TENANT_PHONE : NotificationChannel.EMAIL;
	}

	/** 발송 시간(09:00~20:00 서울) 안에서만 보내고, 그날 못 보내면 버리는지 (M5 결정 3) */
	public boolean windowed() {
		return recipientType != RecipientType.OPS;
	}

}
