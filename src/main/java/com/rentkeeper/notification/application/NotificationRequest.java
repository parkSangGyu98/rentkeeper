package com.rentkeeper.notification.application;

import java.util.Objects;

import com.rentkeeper.notification.domain.NotificationType;
import com.rentkeeper.notification.domain.RecipientType;

/**
 * 만들 알림 한 건. 받는 사람은 ID로만 가리킨다(전화번호·주소·본문은 없다).
 *
 * @param dedupeKey
 *            같은 알림을 두 번 만들지 않기 위한 키 (개인정보를 넣지 않는다)
 */
public record NotificationRequest(NotificationType type, Long landlordId, Long tenantId, Long leaseId, Long chargeId,
		String opsCode, String dedupeKey) {

	public NotificationRequest {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(dedupeKey, "dedupeKey");
		boolean valid = switch (type.recipientType()) {
			case TENANT -> landlordId != null && tenantId != null && leaseId != null && opsCode == null;
			case LANDLORD ->
				landlordId != null && tenantId == null && leaseId == null && chargeId == null && opsCode == null;
			case OPS ->
				landlordId == null && tenantId == null && leaseId == null && chargeId == null && opsCode != null;
		};
		if (!valid) {
			throw new IllegalArgumentException("recipient fields do not match " + type);
		}
	}

	public static NotificationRequest tenant(NotificationType type, long landlordId, long tenantId, long leaseId,
			Long chargeId, String dedupeKey) {
		requireRecipient(type, RecipientType.TENANT);
		return new NotificationRequest(type, landlordId, tenantId, leaseId, chargeId, null, dedupeKey);
	}

	public static NotificationRequest landlord(NotificationType type, long landlordId, String dedupeKey) {
		requireRecipient(type, RecipientType.LANDLORD);
		return new NotificationRequest(type, landlordId, null, null, null, null, dedupeKey);
	}

	public static NotificationRequest ops(String code, String dedupeKey) {
		return new NotificationRequest(NotificationType.OPS_ALERT, null, null, null, null, code, dedupeKey);
	}

	private static void requireRecipient(NotificationType type, RecipientType expected) {
		if (type.recipientType() != expected) {
			throw new IllegalArgumentException(type + " is not for " + expected);
		}
	}

}
