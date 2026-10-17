package com.rentkeeper.lease.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

import com.rentkeeper.common.jpa.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 계약의 월세·관리비 이력 (LEASE-2, V14). 어떤 달의 금액 = 그 달 이하에서 가장 최근 effectiveFrom 행.
 * lease에는 금액이 없고 이 표만 기준이다(M2 결정 5). 금액은 원 단위 long(ADR-003).
 */
@Entity
@Table(name = "lease_rent_history")
public class LeaseRentHistory extends BaseTimeEntity {

	public static final long AMOUNT_MAX = 100_000_000L;

	public static final int REASON_MAX_LENGTH = 200;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "landlord_id", nullable = false, updatable = false)
	private long landlordId;

	@Column(name = "lease_id", nullable = false, updatable = false)
	private long leaseId;

	@Column(name = "monthly_rent", nullable = false)
	private long monthlyRent;

	@Column(name = "maintenance_fee", nullable = false)
	private long maintenanceFee;

	// 청구월 (그 달 1일)
	@Column(name = "effective_from", nullable = false, updatable = false)
	private LocalDate effectiveFrom;

	@Column(length = REASON_MAX_LENGTH)
	private String reason;

	protected LeaseRentHistory() {
	}

	/** 계약을 등록할 때의 첫 금액: 들어오는 달부터 */
	public static LeaseRentHistory initial(Lease lease, long monthlyRent, long maintenanceFee) {
		return create(lease, YearMonth.from(lease.getStartDate()), monthlyRent, maintenanceFee, null);
	}

	static LeaseRentHistory create(Lease lease, YearMonth from, long monthlyRent, long maintenanceFee, String reason) {
		requireAmount(monthlyRent, "monthlyRent");
		requireAmount(maintenanceFee, "maintenanceFee");
		LeaseRentHistory history = new LeaseRentHistory();
		history.landlordId = lease.getLandlordId();
		history.leaseId = Objects.requireNonNull(lease.getId(), "lease must be saved");
		history.monthlyRent = monthlyRent;
		history.maintenanceFee = maintenanceFee;
		history.effectiveFrom = from.atDay(1);
		history.reason = reason == null || reason.isBlank() ? null : reason.strip();
		return history;
	}

	/** 월세·관리비 범위 (PRD 4.3: 0 이상 1억 이하) */
	public static void requireAmount(long amount, String field) {
		if (amount < 0 || amount > AMOUNT_MAX) {
			throw new InvalidLeaseValueException(field);
		}
	}

	/** 한 달 합계 (월세 + 관리비). 합계도 오버플로를 조기에 드러낸다 (ADR-003) */
	public long monthlyTotal() {
		return Math.addExact(monthlyRent, maintenanceFee);
	}

	public YearMonth effectiveMonth() {
		return YearMonth.from(effectiveFrom);
	}

	public Long getId() {
		return id;
	}

	public long getLeaseId() {
		return leaseId;
	}

	public long getMonthlyRent() {
		return monthlyRent;
	}

	public long getMaintenanceFee() {
		return maintenanceFee;
	}

	public LocalDate getEffectiveFrom() {
		return effectiveFrom;
	}

	public String getReason() {
		return reason;
	}

}
