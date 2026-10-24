package com.rentkeeper.notification.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.rentkeeper.billing.application.LandlordNoticeCharges;
import com.rentkeeper.billing.application.TenantNoticeCharges;
import com.rentkeeper.billing.application.TenantNoticeCharges.NoticeTarget;
import com.rentkeeper.common.ops.OpsAlertNotifier;
import com.rentkeeper.lease.application.LeaseEndNotices;
import com.rentkeeper.notification.application.NotificationRules.Planned;
import com.rentkeeper.notification.domain.NotificationType;

/**
 * 날짜로 정해지는 세입자 알림 계획 (NOTI-2·3, M5 결정 4·5). 매일 08:00(서울)에 오늘 해당하는 것만
 * 만든다(NotificationPlanJob). 하루 못 돌아도 지난 날짜를 따라잡지 않는다: 알림이 한꺼번에 몰려 나가는 것보다 하루
 * 빠지는 편을 택했다(위험 5).
 * <p>
 * 계약당 하루 하나만 만든다(지남 > 전날). 다시 돌아도 dedupe_key로 결과가 같다. 실패한 건은 건수만 운영 알림으로 남긴다.
 */
@Component
public class NotificationPlanner {

	private static final Logger log = LoggerFactory.getLogger(NotificationPlanner.class);

	private final TenantNoticeCharges charges;

	private final TenantNotices tenantNotices;

	private final LandlordNotices landlordNotices;

	private final LandlordNoticeCharges landlordCharges;

	private final LeaseEndNotices leaseEnds;

	private final OpsAlertNotifier opsAlerts;

	private final Clock clock;

	NotificationPlanner(TenantNoticeCharges charges, TenantNotices tenantNotices, LandlordNotices landlordNotices,
			LandlordNoticeCharges landlordCharges, LeaseEndNotices leaseEnds, OpsAlertNotifier opsAlerts, Clock clock) {
		this.charges = charges;
		this.tenantNotices = tenantNotices;
		this.landlordNotices = landlordNotices;
		this.landlordCharges = landlordCharges;
		this.leaseEnds = leaseEnds;
		this.opsAlerts = opsAlerts;
		this.clock = clock;
	}

	/**
	 * @param targets
	 *            살펴본 받을 월세 수
	 * @param created
	 *            새로 넣은 알림 수 (보내지 않음으로 넣은 것 포함)
	 * @param failed
	 *            실패한 수
	 */
	public record Result(int targets, int created, int failed) {
	}

	/** 오늘(서울, 주입한 Clock) 기준 */
	public Result planTenantNotices() {
		LocalDate today = LocalDate.now(clock);
		List<NoticeTarget> targets = charges.targets(NotificationRules.dueDatesToPlan(today),
				NotificationRules.plannedStatuses());
		Map<Long, Choice> perLease = new LinkedHashMap<>();
		for (NoticeTarget target : targets) {
			Optional<Planned> planned = NotificationRules.plan(today, target.chargeId(), target.dueDate(),
					target.status());
			// 계약당 하나: 우선순위가 높은 것, 같으면 먼저 나온(청구 ID가 작은) 것
			planned.ifPresent(p -> perLease.merge(target.leaseId(), new Choice(target, p), (a, b) -> NotificationRules
					.priority(b.planned().type()) > NotificationRules.priority(a.planned().type()) ? b : a));
		}
		int created = 0;
		int failed = 0;
		for (Choice choice : perLease.values()) {
			NoticeTarget t = choice.target();
			try {
				if (tenantNotices.enqueue(choice.planned().type(), t.landlordId(), t.leaseId(), t.chargeId(),
						choice.planned().dedupeKey())) {
					created++;
				}
			} catch (RuntimeException e) {
				failed++;
				log.error("notification plan failed: leaseId={}, error={}", t.leaseId(), e.getClass().getSimpleName());
			}
		}
		Result result = new Result(targets.size(), created, failed);
		log.info("notification plan done: today={}, {}", today, result);
		if (failed > 0) {
			opsAlerts.alert(NotificationWorker.ALERT_CODE, "job=plan failed=%d created=%d".formatted(failed, created));
		}
		return result;
	}

	/**
	 * 임대인 요약 메일 계획 (NOTI-4·5): 어제가 납부일이었던 받을 월세가 납부일 지남이 된 임대인, 나가는 날이 90·60·30일 남은
	 * 계약이 있는 임대인에게 하루 한 통씩. 건수가 300건이어도 임대인마다 한 통이다.
	 */
	public Result planLandlordDigests() {
		LocalDate today = LocalDate.now(clock);
		List<Long> overdue = landlordCharges.landlordsWithOverdueDueOn(NotificationRules.overdueDigestDueDate(today));
		List<Long> ending = leaseEnds.landlordsEndingOn(NotificationRules.leaseEndDates(today));
		int created = 0;
		int failed = 0;
		for (long landlordId : overdue) {
			try {
				created += landlordNotices.enqueue(NotificationType.OVERDUE_DIGEST, landlordId,
						NotificationRules.overdueDigestKey(landlordId, today)) ? 1 : 0;
			} catch (RuntimeException e) {
				failed++;
				log.error("notification digest plan failed: landlordId={}, error={}", landlordId,
						e.getClass().getSimpleName());
			}
		}
		for (long landlordId : ending) {
			try {
				created += landlordNotices.enqueue(NotificationType.LEASE_END_DIGEST, landlordId,
						NotificationRules.leaseEndDigestKey(landlordId, today)) ? 1 : 0;
			} catch (RuntimeException e) {
				failed++;
				log.error("notification digest plan failed: landlordId={}, error={}", landlordId,
						e.getClass().getSimpleName());
			}
		}
		Result result = new Result(overdue.size() + ending.size(), created, failed);
		log.info("notification digest plan done: today={}, {}", today, result);
		if (failed > 0) {
			opsAlerts.alert(NotificationWorker.ALERT_CODE,
					"job=digest failed=%d created=%d".formatted(failed, created));
		}
		return result;
	}

	private record Choice(NoticeTarget target, Planned planned) {
	}

}
