package com.rentkeeper.lease.domain;

import java.time.LocalDate;
import java.time.YearMonth;

import com.rentkeeper.common.jpa.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 임대 계약 (LEASE-1~4, V14). 호실·세입자는 id로만 가리킨다: (unit_id, landlord_id)·(tenant_id,
 * landlord_id) 복합 FK가 같은 임대인의 것만 허용한다(ADR-018). 같은 호실의 ACTIVE 계약끼리 [들어오는 날, 나가는
 * 날) 기간이 겹치면 DB가 막는다 (ex_lease_unit_active_period, ADR-019). 월세·관리비는
 * LeaseRentHistory에 있다.
 */
@Entity
@Table(name = "lease")
public class Lease extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "landlord_id", nullable = false, updatable = false)
	private long landlordId;

	@Column(name = "unit_id", nullable = false, updatable = false)
	private long unitId;

	@Column(name = "tenant_id", nullable = false)
	private long tenantId;

	@Column(nullable = false)
	private long deposit;

	@Column(name = "due_day", nullable = false)
	private short dueDay;

	// 들어오는 날은 수정할 수 없다 (M2 결정 12)
	@Column(name = "start_date", nullable = false, updatable = false)
	private LocalDate startDate;

	@Column(name = "end_date", nullable = false)
	private LocalDate endDate;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private LeaseStatus status;

	@Column(name = "ended_on")
	private LocalDate endedOn;

	@Column(name = "bank_name", nullable = false, length = LeaseTerms.BANK_NAME_MAX_LENGTH)
	private String bankName;

	@Convert(converter = LeaseConverters.AccountNo.class)
	@Column(name = "account_no_enc", nullable = false)
	private AccountNumber accountNumber;

	@Convert(converter = LeaseConverters.AccountHolder.class)
	@Column(name = "account_holder_enc", nullable = false)
	private PersonName accountHolder;

	@Column(length = LeaseTerms.MEMO_MAX_LENGTH)
	private String memo;

	protected Lease() {
	}

	/** 새 계약 (ACTIVE). 호실·세입자가 같은 임대인의 것인지는 서비스가 먼저 확인한다(DB 복합 FK가 한 번 더 막는다) */
	public static Lease register(long landlordId, long unitId, long tenantId, LeaseTerms terms) {
		Lease lease = new Lease();
		lease.landlordId = landlordId;
		lease.unitId = unitId;
		lease.tenantId = tenantId;
		lease.startDate = terms.startDate();
		lease.status = LeaseStatus.ACTIVE;
		lease.apply(terms);
		return lease;
	}

	private void apply(LeaseTerms terms) {
		this.deposit = terms.deposit();
		this.dueDay = (short) terms.dueDay();
		this.endDate = terms.endDate();
		this.bankName = terms.bankName();
		this.accountNumber = terms.accountNumber();
		this.accountHolder = terms.accountHolder();
		this.memo = terms.memo();
	}

	/**
	 * 계약 조건을 고친다 (M2 결정 12): 보증금, 받는 날, 나가는 날(연장·단축), 계좌, 메모. 호실과 들어오는 날은 바꿀 수 없다.
	 * 진행 중 계약만.
	 *
	 * @throws LeaseStateException
	 *             종료한 계약일 때
	 */
	public void changeTerms(LeaseTerms terms) {
		requireActive();
		if (!terms.startDate().equals(startDate)) {
			throw new IllegalArgumentException("startDate cannot be changed");
		}
		apply(terms);
	}

	/**
	 * 계약을 종료한다 (LEASE-3): ACTIVE → ENDED. 되돌릴 수 없다. 실제로 끝난 날은 들어오는 날 이후 ~ 오늘까지 (M2
	 * 결정 3: 미래 퇴거는 나가는 날을 고쳐서 처리한다).
	 *
	 * @param today
	 *            서울 날짜 (주입한 Clock 기준)
	 * @throws LeaseStateException
	 *             이미 종료한 계약일 때
	 * @throws InvalidLeaseValueException
	 *             끝난 날이 들어오는 날 전이거나 오늘보다 뒤일 때 (field = endedOn)
	 */
	public void end(LocalDate endedOn, LocalDate today) {
		requireActive();
		if (endedOn == null || endedOn.isBefore(startDate) || endedOn.isAfter(today)) {
			throw new InvalidLeaseValueException("endedOn");
		}
		this.status = LeaseStatus.ENDED;
		this.endedOn = endedOn;
	}

	public boolean isActive() {
		return status == LeaseStatus.ACTIVE;
	}

	/** 계약 기간에 하루라도 들어가는 마지막 달 (나가는 날은 기간 밖이라 그 전날의 달) */
	public YearMonth lastMonth() {
		return YearMonth.from(endDate.minusDays(1));
	}

	private void requireActive() {
		if (status != LeaseStatus.ACTIVE) {
			throw new LeaseStateException();
		}
	}

	/** 종료한 계약은 고칠 수 없다 */
	public static class LeaseStateException extends IllegalStateException {

		LeaseStateException() {
			super("lease is not active");
		}

	}

	/** 오늘 기준 기간 안인지: 들어오는 날 ≤ 오늘 < 나가는 날 ([) 범위) */
	public boolean coversDay(LocalDate day) {
		return !day.isBefore(startDate) && day.isBefore(endDate);
	}

	public Long getId() {
		return id;
	}

	public long getLandlordId() {
		return landlordId;
	}

	public long getUnitId() {
		return unitId;
	}

	public long getTenantId() {
		return tenantId;
	}

	public long getDeposit() {
		return deposit;
	}

	public int getDueDay() {
		return dueDay;
	}

	public LocalDate getStartDate() {
		return startDate;
	}

	public LocalDate getEndDate() {
		return endDate;
	}

	public LeaseStatus getStatus() {
		return status;
	}

	public LocalDate getEndedOn() {
		return endedOn;
	}

	public String getBankName() {
		return bankName;
	}

	public AccountNumber getAccountNumber() {
		return accountNumber;
	}

	public PersonName getAccountHolder() {
		return accountHolder;
	}

	public String getMemo() {
		return memo;
	}

	@Override
	public String toString() {
		return "Lease[id=" + id + ", unitId=" + unitId + ", status=" + status + "]";
	}

}
