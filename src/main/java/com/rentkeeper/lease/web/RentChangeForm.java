package com.rentkeeper.lease.web;

import java.time.YearMonth;

import org.springframework.validation.BindingResult;

import com.rentkeeper.lease.domain.LeaseRentHistory;

/** 월세 변경 폼: 새 월세·관리비, 적용 시작 월(yyyy-MM), 사유(선택) */
public class RentChangeForm {

	private String monthlyRent = "";

	private String maintenanceFee = "";

	private String effectiveMonth = "";

	private String reason = "";

	/** 검증을 마친 값 */
	record RentChange(YearMonth month, long monthlyRent, long maintenanceFee, String reason) {
	}

	/** @return 오류가 있으면 null */
	RentChange toChange(BindingResult result) {
		Long rent = LeaseFields.rent(result, "monthlyRent", monthlyRent);
		Long fee = LeaseFields.fee(result, "maintenanceFee", maintenanceFee);
		YearMonth month = LeaseFields.month(result, "effectiveMonth", effectiveMonth);
		String reasonValue = LeaseFields.plainMemo(result, "reason", reason, LeaseRentHistory.REASON_MAX_LENGTH);
		if (result.hasErrors()) {
			return null;
		}
		return new RentChange(month, rent, fee, reasonValue);
	}

	public String getMonthlyRent() {
		return monthlyRent;
	}

	public void setMonthlyRent(String monthlyRent) {
		this.monthlyRent = monthlyRent;
	}

	public String getMaintenanceFee() {
		return maintenanceFee;
	}

	public void setMaintenanceFee(String maintenanceFee) {
		this.maintenanceFee = maintenanceFee;
	}

	public String getEffectiveMonth() {
		return effectiveMonth;
	}

	public void setEffectiveMonth(String effectiveMonth) {
		this.effectiveMonth = effectiveMonth;
	}

	public String getReason() {
		return reason;
	}

	public void setReason(String reason) {
		this.reason = reason;
	}

}
