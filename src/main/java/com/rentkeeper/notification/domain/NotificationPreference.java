package com.rentkeeper.notification.domain;

import com.rentkeeper.common.jpa.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 임대인 알림 설정 (V21, M5 결정 13). 행이 없으면 {@link #defaults}와 같다 */
@Entity
@Table(name = "notification_preference")
public class NotificationPreference extends BaseTimeEntity {

	@Id
	@Column(name = "landlord_id")
	private Long landlordId;

	@Column(name = "tenant_report", nullable = false)
	private boolean tenantReport;

	@Column(name = "overdue_digest", nullable = false)
	private boolean overdueDigest;

	@Column(name = "lease_end_digest", nullable = false)
	private boolean leaseEndDigest;

	@Column(name = "tenant_sms", nullable = false)
	private boolean tenantSms;

	protected NotificationPreference() {
	}

	/** 기본값: 모두 켬 */
	public static NotificationPreference defaults(long landlordId) {
		NotificationPreference preference = new NotificationPreference();
		preference.landlordId = landlordId;
		preference.change(true, true, true, true);
		return preference;
	}

	public void change(boolean tenantReport, boolean overdueDigest, boolean leaseEndDigest, boolean tenantSms) {
		this.tenantReport = tenantReport;
		this.overdueDigest = overdueDigest;
		this.leaseEndDigest = leaseEndDigest;
		this.tenantSms = tenantSms;
	}

	/** 이 종류의 알림을 보낼지 */
	public boolean allows(NotificationType type) {
		return switch (type) {
			case TENANT_REPORT_DIGEST -> tenantReport;
			case OVERDUE_DIGEST -> overdueDigest;
			case LEASE_END_DIGEST -> leaseEndDigest;
			case CHARGE_ISSUED, DUE_REMINDER, OVERDUE_NOTICE -> tenantSms;
			case OPS_ALERT -> true;
		};
	}

	public long getLandlordId() {
		return landlordId;
	}

	public boolean isTenantReport() {
		return tenantReport;
	}

	public boolean isOverdueDigest() {
		return overdueDigest;
	}

	public boolean isLeaseEndDigest() {
		return leaseEndDigest;
	}

	public boolean isTenantSms() {
		return tenantSms;
	}

}
