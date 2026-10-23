package com.rentkeeper.billing.web;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import org.springframework.validation.BindingResult;

import com.rentkeeper.billing.application.ManualChargeService.NewChargeView;
import com.rentkeeper.billing.domain.ChargeItem;
import com.rentkeeper.common.money.Won;

/** 받을 월세 직접 만들기 폼 (M3-b 결정 8): 고른 달의 월세·관리비를 미리 채우고 고칠 수 있다 */
public class NewChargeForm {

	private String month = "";

	private String monthlyRent = "";

	private String maintenanceFee = "";

	static NewChargeForm of(NewChargeView view) {
		NewChargeForm form = new NewChargeForm();
		form.month = view.month().toString();
		form.monthlyRent = String.valueOf(view.monthlyRent());
		form.maintenanceFee = String.valueOf(view.maintenanceFee());
		return form;
	}

	/** 만들 금액 (형식 검사를 마친 값) */
	record Amounts(YearMonth month, long monthlyRent, long maintenanceFee) {
	}

	/** @return 형식이 틀리면 null */
	Amounts toAmounts(BindingResult result) {
		YearMonth chosen = parseMonth(month);
		if (chosen == null) {
			result.rejectValue("month", "invalid", "만들 달을 골라 주세요");
		}
		Long rent = amount(result, "monthlyRent", monthlyRent, "월세를 숫자로 입력해 주세요 (예: 500,000, 없으면 0)");
		Long fee = maintenanceFee == null || maintenanceFee.isBlank()
				? Long.valueOf(0)
				: amount(result, "maintenanceFee", maintenanceFee, "관리비를 숫자로 입력해 주세요 (없으면 비워 두세요)");
		if (result.hasErrors()) {
			return null;
		}
		return new Amounts(chosen, rent, fee);
	}

	static YearMonth parseMonth(String raw) {
		try {
			return raw == null || raw.isBlank() ? null : YearMonth.parse(raw.strip());
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	private static Long amount(BindingResult result, String field, String raw, String invalid) {
		Long value = Won.parse(raw);
		if (value == null) {
			result.rejectValue(field, "invalid", invalid);
			return null;
		}
		if (value > ChargeItem.AMOUNT_MAX) {
			result.rejectValue(field, "tooLarge", "1억 원까지 입력할 수 있어요");
			return null;
		}
		return value;
	}

	public String getMonth() {
		return month;
	}

	public void setMonth(String month) {
		this.month = month;
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

}
