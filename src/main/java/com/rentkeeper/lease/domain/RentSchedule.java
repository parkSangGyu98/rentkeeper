package com.rentkeeper.lease.domain;

import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 한 계약의 월세 이력 규칙 (LEASE-2, M2 결정 4, ADR-019). "시작됐다" = 적용 시작월이 이번 달 이하.
 * <ul>
 * <li>새 변경의 적용월: 이번 달 이후이고, 첫 달(들어오는 달) 뒤이고, 계약의 마지막 달 이하</li>
 * <li>같은 달에 아직 시작하지 않은 변경이 있으면 바꾼다(교체). 이미 시작된 달이면 거부</li>
 * <li>아직 시작하지 않은 변경은 취소할 수 있다. 첫 행(들어오는 달)은 취소할 수 없고 계약 수정에서만 고친다</li>
 * </ul>
 * 이 규칙은 엔티티 목록만 보고 결정한다. 저장은 서비스가 한다.
 */
public final class RentSchedule {

	/** 규칙 위반의 종류 (화면 문구는 화면이 고른다) */
	public enum Violation {

		/** 종료한 계약 */
		LEASE_ENDED,

		/** 이번 달보다 앞선 달 */
		MONTH_IN_PAST,

		/** 첫 달(들어오는 달) 이하: 계약 수정에서 고친다 */
		FIRST_MONTH,

		/** 계약의 마지막 달 뒤 */
		AFTER_LEASE,

		/** 이미 시작된 달(이번 달)의 변경 */
		ALREADY_STARTED,

		/** 이 계약의 이력이 아님 */
		NOT_FOUND

	}

	public static class RentChangeException extends IllegalArgumentException {

		private final Violation violation;

		RentChangeException(Violation violation) {
			super("rent change rejected: " + violation);
			this.violation = violation;
		}

		public Violation violation() {
			return violation;
		}

	}

	private final Lease lease;

	private final List<LeaseRentHistory> rows;

	private final YearMonth thisMonth;

	/**
	 * @param rows
	 *            이 계약의 이력 (순서 상관없음)
	 * @param thisMonth
	 *            이번 달 (서울, 주입한 Clock 기준)
	 */
	public RentSchedule(Lease lease, List<LeaseRentHistory> rows, YearMonth thisMonth) {
		this.lease = lease;
		this.rows = rows.stream().sorted(Comparator.comparing(LeaseRentHistory::getEffectiveFrom)).toList();
		this.thisMonth = thisMonth;
	}

	/** 첫 행 (들어오는 달) */
	public LeaseRentHistory first() {
		return rows.get(0);
	}

	/** 새 변경으로 고를 수 있는 가장 이른 달 (없으면 빈 값) */
	public Optional<YearMonth> earliestChangeMonth() {
		YearMonth afterFirst = first().effectiveMonth().plusMonths(1);
		YearMonth earliest = afterFirst.isAfter(thisMonth) ? afterFirst : thisMonth;
		return earliest.isAfter(lease.lastMonth()) ? Optional.empty() : Optional.of(earliest);
	}

	public YearMonth latestChangeMonth() {
		return lease.lastMonth();
	}

	/**
	 * 적용월부터 금액을 바꾼다.
	 *
	 * @return 같은 달의 미래 변경을 바꿨으면 그 행, 새로 만들어야 하면 빈 값 (서비스가 {@link #newChange} 로 만든다)
	 */
	public Optional<LeaseRentHistory> change(YearMonth month, long monthlyRent, long maintenanceFee, String reason) {
		if (!lease.isActive()) {
			throw new RentChangeException(Violation.LEASE_ENDED);
		}
		if (month.isBefore(thisMonth)) {
			throw new RentChangeException(Violation.MONTH_IN_PAST);
		}
		if (!month.isAfter(first().effectiveMonth())) {
			throw new RentChangeException(Violation.FIRST_MONTH);
		}
		if (month.isAfter(lease.lastMonth())) {
			throw new RentChangeException(Violation.AFTER_LEASE);
		}
		Optional<LeaseRentHistory> sameMonth = rows.stream().filter(r -> r.effectiveMonth().equals(month)).findFirst();
		if (sameMonth.isPresent()) {
			if (started(sameMonth.get())) {
				throw new RentChangeException(Violation.ALREADY_STARTED);
			}
			sameMonth.get().changeAmounts(monthlyRent, maintenanceFee, reason);
		}
		return sameMonth;
	}

	public LeaseRentHistory newChange(YearMonth month, long monthlyRent, long maintenanceFee, String reason) {
		return LeaseRentHistory.create(lease, month, monthlyRent, maintenanceFee, reason);
	}

	/** 아직 시작하지 않은 변경을 취소할 수 있는지 확인하고 그 행을 돌려준다 (서비스가 지운다) */
	public LeaseRentHistory cancellable(long historyId) {
		if (!lease.isActive()) {
			throw new RentChangeException(Violation.LEASE_ENDED);
		}
		LeaseRentHistory row = rows.stream().filter(r -> r.getId().equals(historyId)).findFirst()
				.orElseThrow(() -> new RentChangeException(Violation.NOT_FOUND));
		if (row == first()) {
			throw new RentChangeException(Violation.FIRST_MONTH);
		}
		if (started(row)) {
			throw new RentChangeException(Violation.ALREADY_STARTED);
		}
		return row;
	}

	/** 첫 행(들어오는 달) 금액 정정: 계약 수정에서만 (M2 결정 4). 청구가 생기면 M3에서 제한한다 */
	public void correctFirst(long monthlyRent, long maintenanceFee) {
		if (!lease.isActive()) {
			throw new RentChangeException(Violation.LEASE_ENDED);
		}
		first().changeAmounts(monthlyRent, maintenanceFee, first().getReason());
	}

	/** 나가는 날을 당기면, 새 마지막 달 뒤로 잡힌 변경이 남으면 안 된다 */
	public boolean hasChangeAfter(YearMonth lastMonth) {
		return rows.stream().anyMatch(r -> r.effectiveMonth().isAfter(lastMonth));
	}

	private boolean started(LeaseRentHistory row) {
		return !row.effectiveMonth().isAfter(thisMonth);
	}

}
