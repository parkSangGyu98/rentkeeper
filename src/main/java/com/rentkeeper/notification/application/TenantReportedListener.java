package com.rentkeeper.notification.application;

import java.time.Clock;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.rentkeeper.billing.application.TenantReportedEvent;
import com.rentkeeper.notification.domain.NotificationType;

/**
 * 세입자가 새로 "입금했어요"를 누르면 임대인에게 알린다 (NOTI-4, M5 결정 4). 임대인마다 한 시간에 한 통으로 묶는다: 같은
 * 시간 칸의 신고는 같은 dedupe_key라 행이 하나다. 메일 본문의 건수는 보낼 때 센다.
 */
@Component
class TenantReportedListener {

	private final LandlordNotices landlordNotices;

	private final SendWindow sendWindow;

	private final Clock clock;

	TenantReportedListener(LandlordNotices landlordNotices, SendWindow sendWindow, Clock clock) {
		this.landlordNotices = landlordNotices;
		this.sendWindow = sendWindow;
		this.clock = clock;
	}

	@EventListener
	void onReported(TenantReportedEvent event) {
		String slot = sendWindow.hourSlot(NotificationType.TENANT_REPORT_DIGEST, clock.instant());
		landlordNotices.enqueue(NotificationType.TENANT_REPORT_DIGEST, event.landlordId(),
				NotificationRules.tenantReportKey(event.landlordId(), slot));
	}

}
