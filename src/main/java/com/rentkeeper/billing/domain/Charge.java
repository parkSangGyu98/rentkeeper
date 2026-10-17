package com.rentkeeper.billing.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;

import com.rentkeeper.common.jpa.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 청구 = 한 계약의 한 달치 받을 월세 (PRD 4.4, V16). 계약 하나에 같은 달은 하나다(uk_charge_lease_month,
 * 취소 포함).
 * <p>
 * <b>상태는 사실에서 계산한다</b> (ADR-023): 취소 여부, 받은 금액 대 총액, 납부일 대 오늘, 세입자 신고, 발행 여부.
 * 사실을 바꾸는 메서드는 끝에서 {@link #refresh}로 상태를 다시 계산하고,
 * 전이표({@link ChargeStatus#canTransitionTo})에 없는 전이면
 * {@link ChargeStateException}을 던진다. 바뀐 전이는 {@link StatusChange}로 돌려주고 서비스가 이력을
 * 남긴다. 상태를 직접 바꾸는 setter는 없다.
 * <p>
 * 총액은 항목 합계, 받은 금액은 지우지 않은 입금 기록의 합계다. 합계 계산은 서비스가 하고 이 엔티티는 결과만 받는다.
 */
@Entity
@Table(name = "charge")
public class Charge extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "landlord_id", nullable = false, updatable = false)
	private long landlordId;

	@Column(name = "lease_id", nullable = false, updatable = false)
	private long leaseId;

	@Column(name = "billing_month", nullable = false, updatable = false)
	private LocalDate billingMonth;

	@Column(name = "due_date", nullable = false)
	private LocalDate dueDate;

	@Column(name = "total_amount", nullable = false)
	private long totalAmount;

	@Column(name = "paid_amount", nullable = false)
	private long paidAmount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ChargeStatus status;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10, updatable = false)
	private ChargeSource source;

	@Column(name = "issued_at")
	private Instant issuedAt;

	@Column(name = "tenant_reported_at")
	private Instant tenantReportedAt;

	@Column(name = "paid_at")
	private Instant paidAt;

	@Column(name = "canceled_at")
	private Instant canceledAt;

	@Column(name = "cancel_reason", length = 200)
	private String cancelReason;

	// 낙관적 락 (PRD 4.4.3). 화면이 보던 번호와 다르면 저장하지 않는다 (M3-b)
	@Version
	@Column(nullable = false)
	private long version;

	protected Charge() {
	}

	/**
	 * 새 청구 (SCHEDULED, 발행 전). 발행은 {@link #issue}로 따로 한다. 계약이 같은 임대인의 것인지는 서비스가 먼저
	 * 확인한다(DB 복합 FK가 한 번 더 막는다).
	 *
	 * @param totalAmount
	 *            항목 합계 (원)
	 */
	public static Charge create(long landlordId, long leaseId, YearMonth month, LocalDate dueDate, long totalAmount,
			ChargeSource source) {
		Objects.requireNonNull(month);
		Objects.requireNonNull(source);
		if (!YearMonth.from(dueDate).equals(month)) {
			throw new IllegalArgumentException("dueDate must be in billing month");
		}
		requireAmount(totalAmount);
		Charge charge = new Charge();
		charge.landlordId = landlordId;
		charge.leaseId = leaseId;
		charge.billingMonth = month.atDay(1);
		charge.dueDate = dueDate;
		charge.totalAmount = totalAmount;
		charge.source = source;
		charge.status = ChargeStatus.SCHEDULED;
		return charge;
	}

	/** 처음 만든 상태 (이력의 첫 행: from 없음) */
	public StatusChange created() {
		return new StatusChange(null, ChargeStatus.SCHEDULED);
	}

	/**
	 * 발행한다: SCHEDULED → ISSUED (M3 결정 6: 생성 직후 배치가 자동 발행). 납부일이 이미 지났어도 먼저 ISSUED가
	 * 되고, 납부일 지남은 이어서 부르는 {@link #refresh}가 바꾼다(전이표에 SCHEDULED → OVERDUE가 없다).
	 */
	public StatusChange issue(Instant now) {
		if (status != ChargeStatus.SCHEDULED) {
			throw new ChargeStateException("not scheduled: " + status);
		}
		this.issuedAt = Objects.requireNonNull(now);
		return moveTo(ChargeStatus.ISSUED, now);
	}

	/**
	 * 사실에서 상태를 다시 계산한다. 납부일 지남 배치도 이것을 부른다.
	 *
	 * @param today
	 *            서울 날짜 (주입한 Clock 기준)
	 * @return 상태가 바뀌었으면 그 전이
	 * @throws ChargeStateException
	 *             계산한 상태로의 전이가 전이표에 없을 때
	 */
	public Optional<StatusChange> refresh(LocalDate today, Instant now) {
		ChargeStatus expected = expectedStatus(today);
		if (expected == status) {
			return Optional.empty();
		}
		return Optional.of(moveTo(expected, now));
	}

	/**
	 * 항목을 고친 뒤 총액을 바꾸고 상태를 다시 계산한다 (M3 결정 12). 총액 ≤ 받은 금액이면 다 받음(PRD 필수 5).
	 *
	 * @param totalAmount
	 *            항목 합계 (원)
	 */
	public Optional<StatusChange> changeTotal(long totalAmount, LocalDate today, Instant now) {
		requireNotCanceled();
		requireAmount(totalAmount);
		this.totalAmount = totalAmount;
		return refresh(today, now);
	}

	/**
	 * 입금을 기록·삭제한 뒤 받은 금액을 바꾸고 상태를 다시 계산한다 (M3-b). 더 받아도 된다(M3 결정 10). 입금을 새로 기록하면
	 * 세입자 신고는 임대인이 확인한 것으로 보고 지운다.
	 *
	 * @param paidAmount
	 *            지우지 않은 입금 기록의 합계 (원)
	 */
	public Optional<StatusChange> changePaid(long paidAmount, LocalDate today, Instant now) {
		requireNotCanceled();
		requireAmount(paidAmount);
		if (paidAmount > this.paidAmount) {
			this.tenantReportedAt = null;
		}
		this.paidAmount = paidAmount;
		return refresh(today, now);
	}

	/** 세입자가 "입금했어요"를 누름 (M4 세입자 링크). 발행한 청구에서 다 받기 전에만 */
	public Optional<StatusChange> reportByTenant(LocalDate today, Instant now) {
		requireNotCanceled();
		if (issuedAt == null || status == ChargeStatus.PAID) {
			throw new ChargeStateException("cannot report: " + status);
		}
		this.tenantReportedAt = Objects.requireNonNull(now);
		return refresh(today, now);
	}

	/** 임대인이 세입자 신고를 반려함 (입금이 확인되지 않음). 납부일이 지났으면 납부일 지남이 된다 */
	public Optional<StatusChange> rejectTenantReport(LocalDate today, Instant now) {
		if (status != ChargeStatus.TENANT_REPORTED) {
			throw new ChargeStateException("not reported: " + status);
		}
		this.tenantReportedAt = null;
		return refresh(today, now);
	}

	/**
	 * 취소한다. 입금 기록이 없을 때만(M3 결정 7). 다 받은 청구는 취소할 수 없다(PRD). 취소는 되돌릴 수 없고 같은 달을 다시 만들
	 * 수 없다(M3 결정 8).
	 */
	public StatusChange cancel(String reason, Instant now) {
		requireNotCanceled();
		if (paidAmount > 0 || !status.canTransitionTo(ChargeStatus.CANCELED)) {
			throw new ChargeStateException("cannot cancel: " + status + ", paid=" + paidAmount);
		}
		String trimmed = reason == null ? "" : reason.strip();
		if (trimmed.isEmpty() || trimmed.length() > 200) {
			throw new IllegalArgumentException("cancel reason is required (1~200)");
		}
		this.cancelReason = trimmed;
		this.canceledAt = Objects.requireNonNull(now);
		return moveTo(ChargeStatus.CANCELED, now);
	}

	/** 사실에서 계산한 상태 (취소는 cancel만 정한다) */
	ChargeStatus expectedStatus(LocalDate today) {
		if (status == ChargeStatus.CANCELED) {
			return ChargeStatus.CANCELED;
		}
		if (paidAmount >= totalAmount) {
			return ChargeStatus.PAID;
		}
		if (issuedAt == null) {
			return paidAmount > 0 ? ChargeStatus.PARTIALLY_PAID : ChargeStatus.SCHEDULED;
		}
		if (tenantReportedAt != null) {
			return ChargeStatus.TENANT_REPORTED;
		}
		// 납부일 당일까지는 기다리고, 다음 날부터 납부일 지남. 일부 받았어도 납부일 지남을 유지한다(M3 결정 7)
		if (today.isAfter(dueDate)) {
			return ChargeStatus.OVERDUE;
		}
		return paidAmount > 0 ? ChargeStatus.PARTIALLY_PAID : ChargeStatus.ISSUED;
	}

	private StatusChange moveTo(ChargeStatus next, Instant now) {
		if (!status.canTransitionTo(next)) {
			throw new ChargeStateException(status + " -> " + next);
		}
		StatusChange change = new StatusChange(status, next);
		this.status = next;
		// DB의 ck_charge_paid_at: 다 받음일 때만 다 받은 시각이 있다
		this.paidAt = next == ChargeStatus.PAID ? Objects.requireNonNull(now) : null;
		return change;
	}

	private void requireNotCanceled() {
		if (status == ChargeStatus.CANCELED) {
			throw new ChargeStateException("canceled");
		}
	}

	private static void requireAmount(long amount) {
		if (amount < 0) {
			throw new IllegalArgumentException("amount must be >= 0");
		}
	}

	/** 남은 금액 (원). 더 받았으면 0 */
	public long balance() {
		return Math.max(0, totalAmount - paidAmount);
	}

	/** 더 받은 금액 (원, M3 결정 10). 덜 받았으면 0 */
	public long overpaid() {
		return Math.max(0, paidAmount - totalAmount);
	}

	public Long getId() {
		return id;
	}

	public long getLandlordId() {
		return landlordId;
	}

	public long getLeaseId() {
		return leaseId;
	}

	public YearMonth getBillingMonth() {
		return YearMonth.from(billingMonth);
	}

	public LocalDate getDueDate() {
		return dueDate;
	}

	public long getTotalAmount() {
		return totalAmount;
	}

	public long getPaidAmount() {
		return paidAmount;
	}

	public ChargeStatus getStatus() {
		return status;
	}

	public ChargeSource getSource() {
		return source;
	}

	public Instant getIssuedAt() {
		return issuedAt;
	}

	public Instant getTenantReportedAt() {
		return tenantReportedAt;
	}

	public Instant getPaidAt() {
		return paidAt;
	}

	public Instant getCanceledAt() {
		return canceledAt;
	}

	public String getCancelReason() {
		return cancelReason;
	}

	public long getVersion() {
		return version;
	}

	@Override
	public String toString() {
		return "Charge[id=" + id + ", leaseId=" + leaseId + ", month=" + billingMonth + ", status=" + status + "]";
	}

}
