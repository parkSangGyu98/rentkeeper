package com.rentkeeper.billing.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.springframework.validation.BindingResult;

import com.rentkeeper.billing.application.PaymentService.PaymentInput;
import com.rentkeeper.billing.domain.PaymentRecord;
import com.rentkeeper.common.money.Won;

/**
 * 입금 기록 폼 ([다 받았어요]·[일부 받았어요]). 형식(숫자·날짜·길이)만 여기서 보고, 금액·날짜의 범위는 서비스가 정한다
 * (PaymentService). 레코드가 아니라 일반 객체다: 칸별 오류를 rejectValue로 넣는다(M2 계약 폼과 같은 이유).
 */
public class PaymentForm {

	static final String AMOUNT_INVALID = "받은 금액을 숫자로 입력해 주세요 (예: 500,000)";

	private String amount = "";

	private String paidOn = "";

	private String memo = "";

	private long version;

	/** [다 받았어요]: 남은 금액 그대로 기록 (금액 칸 없음) */
	private boolean full;

	/** 남은 금액보다 많아도 기록한다고 확인함 (M3-b 결정 3) */
	private boolean overpayConfirmed;

	static PaymentForm of(long version, boolean full, long balance, LocalDate today) {
		PaymentForm form = new PaymentForm();
		form.version = version;
		form.full = full;
		form.amount = full ? String.valueOf(balance) : "";
		form.paidOn = today.toString();
		return form;
	}

	/** @return 형식이 틀리면 null (오류는 result에 칸별로) */
	PaymentInput toInput(BindingResult result) {
		Long value = Won.parse(amount);
		if (value == null) {
			result.rejectValue("amount", "invalid", AMOUNT_INVALID);
		}
		LocalDate day = null;
		try {
			day = paidOn == null || paidOn.isBlank() ? null : LocalDate.parse(paidOn.strip());
		} catch (DateTimeParseException e) {
			// 아래에서 안내한다
		}
		if (day == null) {
			result.rejectValue("paidOn", "invalid", "받은 날을 골라 주세요");
		}
		String note = memo == null ? "" : memo.strip();
		if (note.codePointCount(0, note.length()) > PaymentRecord.MEMO_MAX_LENGTH) {
			result.rejectValue("memo", "tooLong", "메모는 200자 이하로 입력해 주세요");
		}
		if (result.hasErrors()) {
			return null;
		}
		return new PaymentInput(value, day, note, overpayConfirmed);
	}

	public String getAmount() {
		return amount;
	}

	public void setAmount(String amount) {
		this.amount = amount;
	}

	public String getPaidOn() {
		return paidOn;
	}

	public void setPaidOn(String paidOn) {
		this.paidOn = paidOn;
	}

	public String getMemo() {
		return memo;
	}

	public void setMemo(String memo) {
		this.memo = memo;
	}

	public long getVersion() {
		return version;
	}

	public void setVersion(long version) {
		this.version = version;
	}

	public boolean isFull() {
		return full;
	}

	public void setFull(boolean full) {
		this.full = full;
	}

	public boolean isOverpayConfirmed() {
		return overpayConfirmed;
	}

	public void setOverpayConfirmed(boolean overpayConfirmed) {
		this.overpayConfirmed = overpayConfirmed;
	}

	@Override
	public String toString() {
		return "PaymentForm[version=" + version + ", full=" + full + "]";
	}

}
