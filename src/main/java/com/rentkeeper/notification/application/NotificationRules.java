package com.rentkeeper.notification.application;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.rentkeeper.billing.domain.ChargeStatus;
import com.rentkeeper.notification.domain.NotificationType;

/**
 * 세입자 알림을 언제 만드는지 (M5 결정 4·5, ADR-028). 규칙은 여기 한 곳에만 둔다.
 * <ul>
 * <li>받을 월세 안내: 발행할 때, 납부일이 오늘이거나 그 뒤일 때만. 누락 복구로 지난 달 것을 한꺼번에 발행해도 알림은 없다</li>
 * <li>납부일 전날 안내: 납부일 하루 전 한 번 (PRD의 "당일/전일" 중 전날만)</li>
 * <li>납부일 지남 안내: 납부일 다음 날(D+1)과 7일 뒤(D+7) (PRD의 D+1·3·7 중 3을 뺌). 납부일 지남 상태만,
 * 세입자 신고 상태는 빠진다</li>
 * <li>계획 배치는 그날 해당하는 것만 만든다. 하루 못 돌아도 지난 날짜를 따라잡지 않는다</li>
 * <li>세입자는 계약당 하루 1통. 겹치면 지남 > 전날 > 안내 순으로 하나만</li>
 * </ul>
 */
public final class NotificationRules {

	/** 납부일 지남 안내를 보내는 날: 납부일 뒤 며칠 */
	public static final List<Integer> OVERDUE_NOTICE_DAYS = List.of(1, 7);

	private NotificationRules() {
	}

	/** 발행한 받을 월세에 안내를 만들지 (누락 복구분 금지) */
	public static boolean noticeOnIssue(LocalDate dueDate, LocalDate today) {
		return !dueDate.isBefore(today);
	}

	/** 계획 배치가 오늘 살펴볼 납부일들: 내일(전날 안내), D+1·D+7(지남 안내) */
	public static List<LocalDate> dueDatesToPlan(LocalDate today) {
		return List.of(today.plusDays(1), today.minusDays(OVERDUE_NOTICE_DAYS.get(0)),
				today.minusDays(OVERDUE_NOTICE_DAYS.get(1)));
	}

	/** 계획 배치가 볼 상태 */
	public static Set<ChargeStatus> plannedStatuses() {
		Set<ChargeStatus> statuses = EnumSet.copyOf(ChargeStatus.dueReminderTargets());
		statuses.addAll(ChargeStatus.overdueNoticeTargets());
		return statuses;
	}

	/**
	 * 오늘 이 받을 월세에 계획할 알림.
	 *
	 * @return 종류와 dedupe_key. 오늘 해당하지 않으면 빈 값
	 */
	public static Optional<Planned> plan(LocalDate today, long chargeId, LocalDate dueDate, ChargeStatus status) {
		long daysAfterDue = ChronoUnit.DAYS.between(dueDate, today);
		if (daysAfterDue == -1 && ChargeStatus.dueReminderTargets().contains(status)) {
			return Optional.of(new Planned(NotificationType.DUE_REMINDER, "DUE_REMINDER:" + chargeId));
		}
		if (OVERDUE_NOTICE_DAYS.contains((int) daysAfterDue) && ChargeStatus.overdueNoticeTargets().contains(status)) {
			return Optional.of(
					new Planned(NotificationType.OVERDUE_NOTICE, "OVERDUE_NOTICE:" + chargeId + ":D" + daysAfterDue));
		}
		return Optional.empty();
	}

	/** 곧 끝나는 계약 알림: 나가는 날까지 남은 날 수 (NOTI-5) */
	public static final List<Integer> LEASE_END_DAYS = List.of(90, 60, 30);

	/** 오늘 곧 끝나는 계약 요약에 넣을 나가는 날들 */
	public static List<LocalDate> leaseEndDates(LocalDate today) {
		return LEASE_END_DAYS.stream().map(today::plusDays).toList();
	}

	/** 납부일 지남 요약이 다루는 납부일: 어제 (오늘 00:10에 납부일 지남이 된 것) */
	public static LocalDate overdueDigestDueDate(LocalDate day) {
		return day.minusDays(1);
	}

	public static String tenantReportKey(long landlordId, String hourSlot) {
		return "TENANT_REPORT:" + landlordId + ":" + hourSlot;
	}

	public static String overdueDigestKey(long landlordId, LocalDate today) {
		return "OVERDUE_DIGEST:" + landlordId + ":" + today;
	}

	public static String leaseEndDigestKey(long landlordId, LocalDate today) {
		return "LEASE_END_DIGEST:" + landlordId + ":" + today;
	}

	/** 받을 월세 안내의 dedupe_key */
	public static String chargeIssuedKey(long chargeId) {
		return "CHARGE_ISSUED:" + chargeId;
	}

	/** 보낼 때 받을 월세 상태가 아직 이 알림에 맞는지 (아니면 STALE) */
	public static boolean stillValid(NotificationType type, ChargeStatus status) {
		return switch (type) {
			case CHARGE_ISSUED, DUE_REMINDER -> ChargeStatus.dueReminderTargets().contains(status);
			case OVERDUE_NOTICE -> ChargeStatus.overdueNoticeTargets().contains(status);
			default -> throw new IllegalArgumentException(type + " is not a tenant notice");
		};
	}

	/** 같은 계약에 같은 날 겹칠 때 우선순위 (클수록 먼저) */
	public static int priority(NotificationType type) {
		return switch (type) {
			case OVERDUE_NOTICE -> 3;
			case DUE_REMINDER -> 2;
			case CHARGE_ISSUED -> 1;
			default -> 0;
		};
	}

	/** 납부일 지남 안내가 D+7(두 번째)인지 */
	public static boolean secondOverdueNotice(LocalDate dueDate, LocalDate scheduledFor) {
		return ChronoUnit.DAYS.between(dueDate, scheduledFor) >= OVERDUE_NOTICE_DAYS.get(1);
	}

	/**
	 * 계획한 알림 한 건
	 *
	 * @param dedupeKey
	 *            종류·청구 ID(·D+n)
	 */
	public record Planned(NotificationType type, String dedupeKey) {
	}

}
