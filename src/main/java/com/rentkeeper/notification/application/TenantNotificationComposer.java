package com.rentkeeper.notification.application;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.rentkeeper.billing.application.TenantNoticeCharges;
import com.rentkeeper.billing.application.TenantNoticeCharges.NoticeCharge;
import com.rentkeeper.common.config.AppProperties;
import com.rentkeeper.lease.application.TenantLinkService;
import com.rentkeeper.lease.application.TenantLinkService.NotificationLink;
import com.rentkeeper.lease.application.TenantNoticeRecipients;
import com.rentkeeper.lease.application.TenantNoticeRecipients.NoticeRecipient;
import com.rentkeeper.notification.domain.Notification;
import com.rentkeeper.notification.domain.NotificationRepository;
import com.rentkeeper.notification.domain.NotificationStatus;
import com.rentkeeper.notification.domain.NotificationType;
import com.rentkeeper.notification.domain.SkipReason;

/**
 * 세입자 알림 3종의 본문 (NOTI-1~3). 보낼 때 다시 확인한다: 수신 거부, 알림 멈춤, 받을 월세 상태, 계약당 하루 1통. 모두
 * 통과해야 마지막에 알림 링크를 만든다(보내지 않는 알림에는 링크를 만들지 않는다, M5 추가 승인). 번호·본문·링크 원문은 돌려줄 뿐
 * 저장하지 않는다.
 */
@Component
class TenantNotificationComposer implements NotificationComposer {

	private static final Set<NotificationType> TYPES = EnumSet.of(NotificationType.CHARGE_ISSUED,
			NotificationType.DUE_REMINDER, NotificationType.OVERDUE_NOTICE);

	private final TenantNoticeRecipients recipients;

	private final TenantNoticeCharges charges;

	private final TenantLinkService tenantLinks;

	private final NotificationRepository notifications;

	private final AppProperties appProperties;

	TenantNotificationComposer(TenantNoticeRecipients recipients, TenantNoticeCharges charges,
			TenantLinkService tenantLinks, NotificationRepository notifications, AppProperties appProperties) {
		this.recipients = recipients;
		this.charges = charges;
		this.tenantLinks = tenantLinks;
		this.notifications = notifications;
		this.appProperties = appProperties;
	}

	@Override
	public boolean supports(NotificationType type) {
		return TYPES.contains(type);
	}

	@Override
	public Composition compose(Notification n, Instant now) {
		long landlordId = n.getLandlordId();
		long leaseId = n.getLeaseId();
		Optional<NoticeRecipient> recipient = recipients.recipient(landlordId, leaseId);
		if (recipient.isEmpty()) {
			return new Composition.Skip(SkipReason.STALE);
		}
		Optional<SkipReason> skip = TenantNotices.skipReason(recipient.get());
		if (skip.isPresent()) {
			return new Composition.Skip(skip.get());
		}
		Optional<NoticeCharge> charge = charges.charge(landlordId, leaseId, n.getChargeId());
		if (charge.isEmpty() || !NotificationRules.stillValid(n.getType(), charge.get().status())) {
			return new Composition.Skip(SkipReason.STALE);
		}
		if (notifications.countByLandlordIdAndLeaseIdAndScheduledForAndStatusAndIdNot(landlordId, leaseId,
				n.getScheduledFor(), NotificationStatus.SENT, n.getId()) > 0) {
			return new Composition.Skip(SkipReason.DAILY_CAP);
		}
		NotificationLink link = tenantLinks.issueForNotification(landlordId, leaseId);
		if (link.link() == null) {
			return new Composition.Skip(switch (link.unavailable()) {
				case PAUSED_BY_LANDLORD -> SkipReason.LINK_CLOSED_BY_LANDLORD;
				case PERIOD_OVER -> SkipReason.LEASE_ENDED;
			});
		}
		String url = appProperties.tenantLink(link.link().rawToken());
		return new Composition.Send(recipient.get().phone(), NotificationTemplates.TENANT_SUBJECT,
				body(n, recipient.get().unitName(), charge.get(), url));
	}

	private static String body(Notification n, String unitName, NoticeCharge c, String url) {
		return switch (n.getType()) {
			case CHARGE_ISSUED ->
				NotificationTemplates.chargeIssued(unitName, c.month(), c.balance(), c.dueDate(), url);
			case DUE_REMINDER -> NotificationTemplates.dueReminder(unitName, c.month(), c.dueDate(), url);
			case OVERDUE_NOTICE -> NotificationRules.secondOverdueNotice(c.dueDate(), n.getScheduledFor())
					? NotificationTemplates.overdueSecond(unitName, c.month(), c.balance(), url)
					: NotificationTemplates.overdueFirst(unitName, c.month(), url);
			default -> throw new IllegalArgumentException(n.getType().name());
		};
	}

}
