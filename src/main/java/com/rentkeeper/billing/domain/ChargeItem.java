package com.rentkeeper.billing.domain;

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
 * 청구 항목 (CHG-3, V16). 청구는 id로만 가리킨다: (charge_id, landlord_id) 복합 FK가 같은 임대인의
 * 청구만 허용한다. 금액을 고치면 서비스가 항목 변경 이력(ChargeItemHistory)을 남기고 청구 총액을 다시 계산한다(M3 결정
 * 12).
 */
@Entity
@Table(name = "charge_item")
public class ChargeItem extends BaseTimeEntity {

	public static final int LABEL_MAX_LENGTH = 50;

	public static final int MEMO_MAX_LENGTH = 200;

	/** 항목 하나의 최대 금액 (원) */
	public static final long AMOUNT_MAX = 100_000_000L;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "landlord_id", nullable = false, updatable = false)
	private long landlordId;

	@Column(name = "charge_id", nullable = false, updatable = false)
	private long chargeId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20, updatable = false)
	private ChargeItemType type;

	@Column(nullable = false, length = LABEL_MAX_LENGTH)
	private String label;

	@Column(nullable = false)
	private long amount;

	@Column(length = MEMO_MAX_LENGTH)
	private String memo;

	protected ChargeItem() {
	}

	/** 청구에 붙일 새 항목. 이름은 앞뒤 공백을 지운다 */
	public static ChargeItem of(Charge charge, ChargeItemType type, String label, long amount) {
		Objects.requireNonNull(charge.getId(), "charge must be saved first");
		ChargeItem item = new ChargeItem();
		item.landlordId = charge.getLandlordId();
		item.chargeId = charge.getId();
		item.type = Objects.requireNonNull(type);
		item.label = requireLabel(label);
		item.amount = requireAmount(amount);
		return item;
	}

	/** 임대인이 추가한 항목 (메모 포함, M3 결정 12) */
	public static ChargeItem of(Charge charge, ChargeItemType type, String label, long amount, String memo) {
		ChargeItem item = of(charge, type, label, amount);
		item.memo = requireMemo(memo);
		return item;
	}

	/** 이름·금액·메모를 고친다 (종류는 바꾸지 않는다). 바뀐 내용 기록과 합계 계산은 서비스가 한다 */
	public void change(String label, long amount, String memo) {
		this.label = requireLabel(label);
		this.amount = requireAmount(amount);
		this.memo = requireMemo(memo);
	}

	private static String requireMemo(String memo) {
		if (memo == null || memo.isBlank()) {
			return null;
		}
		String trimmed = memo.strip();
		if (trimmed.codePointCount(0, trimmed.length()) > MEMO_MAX_LENGTH) {
			throw new IllegalArgumentException("memo too long");
		}
		return trimmed;
	}

	public static long requireAmount(long amount) {
		if (amount < 0 || amount > AMOUNT_MAX) {
			throw new IllegalArgumentException("item amount out of range");
		}
		return amount;
	}

	private static String requireLabel(String label) {
		String trimmed = label == null ? "" : label.strip();
		if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > LABEL_MAX_LENGTH) {
			throw new IllegalArgumentException("label is required (1~50)");
		}
		return trimmed;
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

	public ChargeItemType getType() {
		return type;
	}

	public String getLabel() {
		return label;
	}

	public long getAmount() {
		return amount;
	}

	public String getMemo() {
		return memo;
	}

}
