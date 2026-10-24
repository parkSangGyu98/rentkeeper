package com.rentkeeper.notification.application;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.rentkeeper.billing.application.ChargeIssuedEvent;
import com.rentkeeper.notification.domain.NotificationType;

/**
 * 받을 월세를 발행하면 받을 월세 안내를 만든다 (NOTI-1, M5 결정 1·5). 발행한 트랜잭션 안에서 동기로 듣는다: 발행이 롤백되면
 * 알림도 없다. 납부일이 지난 것(누락 복구분·지난 달 직접 만든 것)은 만들지 않는다.
 */
@Component
class ChargeIssuedListener {

	private final TenantNotices tenantNotices;

	ChargeIssuedListener(TenantNotices tenantNotices) {
		this.tenantNotices = tenantNotices;
	}

	@EventListener
	void onIssued(ChargeIssuedEvent event) {
		if (!NotificationRules.noticeOnIssue(event.dueDate(), event.today())) {
			return;
		}
		tenantNotices.enqueue(NotificationType.CHARGE_ISSUED, event.landlordId(), event.leaseId(), event.chargeId(),
				NotificationRules.chargeIssuedKey(event.chargeId()));
	}

}
