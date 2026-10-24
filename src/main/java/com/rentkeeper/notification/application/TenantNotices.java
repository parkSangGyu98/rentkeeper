package com.rentkeeper.notification.application;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.rentkeeper.lease.application.TenantNoticeRecipients;
import com.rentkeeper.lease.application.TenantNoticeRecipients.NoticeRecipient;
import com.rentkeeper.notification.domain.NotificationType;
import com.rentkeeper.notification.domain.SkipReason;

/**
 * 세입자 알림 행 넣기 (발행 이벤트·계획 배치가 함께 쓴다). 만들 때 수신 거부·알림 멈춤을 보고, 해당하면 "보내지 않음"으로 남긴다.
 * 보낼 때 한 번 더 본다(TenantNotificationComposer).
 */
@Component
class TenantNotices {

	private final TenantNoticeRecipients recipients;

	private final NotificationOutbox outbox;

	TenantNotices(TenantNoticeRecipients recipients, NotificationOutbox outbox) {
		this.recipients = recipients;
		this.outbox = outbox;
	}

	/** @return 새로 넣었으면 true (같은 알림이 있거나 계약이 없으면 false) */
	boolean enqueue(NotificationType type, long landlordId, long leaseId, long chargeId, String dedupeKey) {
		Optional<NoticeRecipient> recipient = recipients.recipient(landlordId, leaseId);
		if (recipient.isEmpty()) {
			return false;
		}
		NotificationRequest request = NotificationRequest.tenant(type, landlordId, recipient.get().tenantId(), leaseId,
				chargeId, dedupeKey);
		Optional<SkipReason> skip = skipReason(recipient.get());
		return skip.isPresent() ? outbox.enqueueSkipped(request, skip.get()) : outbox.enqueue(request);
	}

	/** 수신 거부·알림 멈춤 (만들 때와 보낼 때 같은 기준) */
	static Optional<SkipReason> skipReason(NoticeRecipient recipient) {
		if (recipient.optedOut()) {
			return Optional.of(SkipReason.OPTED_OUT);
		}
		if (recipient.paused()) {
			return Optional.of(SkipReason.LINK_CLOSED_BY_LANDLORD);
		}
		return Optional.empty();
	}

}
