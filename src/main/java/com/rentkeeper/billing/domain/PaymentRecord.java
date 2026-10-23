package com.rentkeeper.billing.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

import com.rentkeeper.common.jpa.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 입금 기록 한 건 (PAY-1·2·4, V16). 전액 입금도 남은 금액만큼의 기록 하나다. 지우거나 고칠 때 행을 지우지 않고 지운
 * 시각·사유를 남긴다(감사 기록, M3 결정 11). 청구의 받은 금액은 지우지 않은 기록의 합계다.
 */
@Entity
@Table(name = "payment_record")
public class PaymentRecord extends BaseTimeEntity {

	/** 한 번에 기록할 수 있는 최대 금액 (원, DB ck_payment_record_amount) */
	public static final long AMOUNT_MAX = 1_000_000_000L;

	public static final int MEMO_MAX_LENGTH = 200;

	public static final int REASON_MAX_LENGTH = 200;

	public enum Source {

		/** 임대인이 직접 입력 */
		MANUAL,

		/** 세입자 신고를 임대인이 확인해 기록 (M4) */
		TENANT_REPORT

	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "landlord_id", nullable = false, updatable = false)
	private long landlordId;

	@Column(name = "charge_id", nullable = false, updatable = false)
	private long chargeId;

	@Column(nullable = false, updatable = false)
	private long amount;

	@Column(name = "paid_on", nullable = false, updatable = false)
	private LocalDate paidOn;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20, updatable = false)
	private Source source;

	@Column(length = MEMO_MAX_LENGTH, updatable = false)
	private String memo;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "delete_reason", length = REASON_MAX_LENGTH)
	private String deleteReason;

	@Column(name = "replaces_id", updatable = false)
	private Long replacesId;

	protected PaymentRecord() {
	}

	/**
	 * 임대인이 기록한 입금. 받은 날의 범위(오늘 이하, 계약 시작월 1일 이후)는 서비스가 확인한다.
	 *
	 * @param memo
	 *            비어 있으면 저장하지 않는다
	 * @throws IllegalArgumentException
	 *             금액이 1원 ~ 10억 원 밖일 때
	 */
	public static PaymentRecord manual(Charge charge, long amount, LocalDate paidOn, String memo) {
		Objects.requireNonNull(charge.getId(), "charge must be saved first");
		if (amount < 1 || amount > AMOUNT_MAX) {
			throw new IllegalArgumentException("payment amount out of range");
		}
		PaymentRecord record = new PaymentRecord();
		record.landlordId = charge.getLandlordId();
		record.chargeId = charge.getId();
		record.amount = amount;
		record.paidOn = Objects.requireNonNull(paidOn);
		record.source = Source.MANUAL;
		record.memo = blankToNull(memo);
		return record;
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

	static String blankToNull(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.strip();
	}

	public Long getId() {
		return id;
	}

	public long getLandlordId() {
		return landlordId;
	}

	public long getChargeId() {
		return chargeId;
	}

	public long getAmount() {
		return amount;
	}

	public LocalDate getPaidOn() {
		return paidOn;
	}

	public Source getSource() {
		return source;
	}

	public String getMemo() {
		return memo;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public String getDeleteReason() {
		return deleteReason;
	}

	public Long getReplacesId() {
		return replacesId;
	}

}
