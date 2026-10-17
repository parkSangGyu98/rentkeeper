package com.rentkeeper.billing.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.rentkeeper.billing.domain.ChargeCalendar;
import com.rentkeeper.lease.application.LeaseBillingTerms;

/**
 * 자동으로 만들 청구 달을 정한다 (ADR-024). 저장하지 않고 계산만 한다.
 * <ol>
 * <li>대상 달: 들어오는 달 ~ 마지막 청구월((실제 끝 − 하루)의 달, M3 결정 4)</li>
 * <li>누락 복구 하한: 이번 달 기준 {@value #RECOVERY_MONTHS}개월 전 달부터 (M3 결정 21)</li>
 * <li>계약 등록일 이후가 납부일인 달부터 (M3 결정 2). 등록 전에 납부일이 지난 달은 임대인이 직접 만든다</li>
 * <li>만들 날(납부일 − issueDaysBefore)이 오늘 이하인 달만</li>
 * <li>이미 청구가 있는 달(취소 포함)은 건너뛴다 (M3 결정 8)</li>
 * <li>월세 + 관리비가 0원인 달은 만들지 않는다 (M3 결정 20)</li>
 * </ol>
 */
public final class ChargePlanner {

	/** 누락 복구 상한 (개월) */
	public static final int RECOVERY_MONTHS = 12;

	private ChargePlanner() {
	}

	/** 만들 청구 한 달치 (금액은 원) */
	public record PlannedCharge(YearMonth month, LocalDate dueDate, long monthlyRent, long maintenanceFee) {

		public long total() {
			return Math.addExact(monthlyRent, maintenanceFee);
		}

	}

	/**
	 * @param today
	 *            서울 날짜 (주입한 Clock 기준)
	 * @param existingMonths
	 *            이 계약에 이미 있는 청구 달 (취소 포함)
	 */
	public static List<PlannedCharge> plan(LeaseBillingTerms terms, LocalDate today, Set<YearMonth> existingMonths) {
		Optional<YearMonth> last = terms.lastMonth();
		if (last.isEmpty()) {
			return List.of();
		}
		YearMonth recoveryFloor = YearMonth.from(today).minusMonths(RECOVERY_MONTHS);
		YearMonth month = terms.firstMonth().isBefore(recoveryFloor) ? recoveryFloor : terms.firstMonth();
		List<PlannedCharge> planned = new ArrayList<>();
		for (; !month.isAfter(last.get()); month = month.plusMonths(1)) {
			if (ChargeCalendar.issueDate(month, terms.dueDay(), terms.issueDaysBefore()).isAfter(today)) {
				break; // 뒤의 달은 더 늦다
			}
			LocalDate due = ChargeCalendar.dueDate(month, terms.dueDay());
			if (due.isBefore(terms.registeredOn()) || existingMonths.contains(month)) {
				continue;
			}
			Optional<LeaseBillingTerms.Rent> rent = terms.rentFor(month);
			if (rent.isEmpty() || rent.get().monthlyRent() + rent.get().maintenanceFee() == 0) {
				continue;
			}
			planned.add(new PlannedCharge(month, due, rent.get().monthlyRent(), rent.get().maintenanceFee()));
		}
		return planned;
	}

}
