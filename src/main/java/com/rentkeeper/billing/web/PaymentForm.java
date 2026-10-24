package com.rentkeeper.billing.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.springframework.validation.BindingResult;

import com.rentkeeper.billing.application.ChargeViews.PaymentRow;
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

	/** 고치거나 지우는 사유 (정정·삭제 화면, M3 결정 11) */
	private String reason = "";

	private long version;

	/** [다 받았어요]: 남은 금액 그대로 기록 (금액 칸 없음) */
	private boolean full;

	/** 남은 금액보다 많아도 기록한다고 확인함 (M3-b 결정 3) */
	private boolean overpayConfirmed;

	/** 세입자 신고의 [입금 확인하기]에서 옴 (M4 결정 11). 기록 경로가 TENANT_REPORT가 된다 */
	private boolean fromReport;

	/** 세입자 신고 확인: 금액은 남은 금액, 받은 날은 신고한 날로 채운다(고칠 수 있다) */
	static PaymentForm confirmingReport(long version, long balance, LocalDate reportedOn) {
		PaymentForm form = new PaymentForm();
		form.version = version;
		form.amount = String.valueOf(balance);
		form.paidOn = reportedOn.toString();
		form.fromReport = true;
		return form;
	}

	/** 정정 화면: 원래 기록의 값으로 채운다 */
	static PaymentForm correcting(long version, PaymentRow original) {
		PaymentForm form = new PaymentForm();
		form.version = version;
		form.amount = String.valueOf(original.amount());
		form.paidOn = original.paidOn().toString();
		form.memo = original.memo() == null ? "" : original.memo();
		return form;
	}

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
		return new PaymentInput(value, day, note, overpayConfirmed, fromReport);
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

	public String getReason() {
		return reason;
	}

	public void setReason(String reason) {
		this.reason = reason;
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

	public boolean isFromReport() {
		return fromReport;
	}

	public void setFromReport(boolean fromReport) {
		this.fromReport = fromReport;
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
