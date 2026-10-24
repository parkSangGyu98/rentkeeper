package com.rentkeeper.notification.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.rentkeeper.billing.application.LandlordNoticeCharges;
import com.rentkeeper.common.config.AppProperties;
import com.rentkeeper.lease.application.LeaseEndNotices;
import com.rentkeeper.notification.application.NotificationTemplates.Mail;
import com.rentkeeper.notification.domain.Notification;
import com.rentkeeper.notification.domain.NotificationType;
import com.rentkeeper.notification.domain.SkipReason;

/**
 * 임대인 요약 메일 3종 (NOTI-4·5). 보낼 때 설정·이메일 인증을 다시 보고, 건수를 그때 센다. 보낼 것이 없어졌으면(이미 다
 * 확인함) 보내지 않는다(STALE). 세입자 이름·연락처는 넣지 않는다.
 */
@Component
class LandlordNotificationComposer implements NotificationComposer {

	private static final Set<NotificationType> TYPES = EnumSet.of(NotificationType.TENANT_REPORT_DIGEST,
			NotificationType.OVERDUE_DIGEST, NotificationType.LEASE_END_DIGEST);

	private final LandlordNotices landlordNotices;

	private final LandlordNoticeCharges charges;

	private final LeaseEndNotices leaseEnds;

	private final AppProperties appProperties;

	LandlordNotificationComposer(LandlordNotices landlordNotices, LandlordNoticeCharges charges,
			LeaseEndNotices leaseEnds, AppProperties appProperties) {
		this.landlordNotices = landlordNotices;
		this.charges = charges;
		this.leaseEnds = leaseEnds;
		this.appProperties = appProperties;
	}

	@Override
	public boolean supports(NotificationType type) {
		return TYPES.contains(type);
	}

	@Override
	public Composition compose(Notification n, Instant now) {
		long landlordId = n.getLandlordId();
		Optional<SkipReason> skip = landlordNotices.skipReason(n.getType(), landlordId);
		if (skip.isPresent()) {
			return new Composition.Skip(skip.get());
		}
		Optional<Mail> mail = mail(n, landlordId);
		if (mail.isEmpty()) {
			return new Composition.Skip(SkipReason.STALE);
		}
		return new Composition.Send(landlordNotices.verifiedEmail(landlordId).orElseThrow(), mail.get().subject(),
				mail.get().body());
	}

	private Optional<Mail> mail(Notification n, long landlordId) {
		LocalDate day = n.getScheduledFor();
		return switch (n.getType()) {
			case TENANT_REPORT_DIGEST -> {
				int count = charges.tenantReported(landlordId);
				yield count == 0
						? Optional.empty()
						: Optional.of(NotificationTemplates.tenantReports(count, appProperties.link("/app/dashboard")));
			}
			case OVERDUE_DIGEST -> {
				int count = charges.overdueDueOn(landlordId, NotificationRules.overdueDigestDueDate(day));
				yield count == 0
						? Optional.empty()
						: Optional.of(NotificationTemplates.overdueDigest(count, appProperties.link("/app/charges")));
			}
			case LEASE_END_DIGEST -> {
				List<String> lines = leaseEnds.endingOn(landlordId, NotificationRules.leaseEndDates(day)).stream()
						.map(e -> NotificationTemplates.leaseEndLine(e.unitName(), e.endDate(),
								ChronoUnit.DAYS.between(day, e.endDate())))
						.toList();
				yield lines.isEmpty()
						? Optional.empty()
						: Optional
								.of(NotificationTemplates.leaseEndDigest(lines, appProperties.link("/app/dashboard")));
			}
			default -> throw new IllegalArgumentException(n.getType().name());
		};
	}

}
