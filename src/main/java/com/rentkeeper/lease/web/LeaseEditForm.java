package com.rentkeeper.lease.web;

import java.time.LocalDate;

import org.springframework.validation.BindingResult;

import com.rentkeeper.lease.application.LeaseChangeService.LeaseEdit;
import com.rentkeeper.lease.domain.AccountNumber;
import com.rentkeeper.lease.domain.LeaseTerms;
import com.rentkeeper.lease.domain.PersonName;
import com.rentkeeper.lease.domain.PhoneNumber;
import com.rentkeeper.lease.domain.PrivateMemo;

/**
 * 계약 수정 폼 (M2 결정 12). 호실과 들어오는 날은 없다(바꿀 수 없음). 첫 달 월세·관리비 정정을 함께 받는다(M2 결정 4).
 */
public class LeaseEditForm {

	private String tenantName = "";

	private String tenantPhone = "";

	private String tenantMemo = "";

	private String deposit = "";

	private String firstMonthlyRent = "";

	private String firstMaintenanceFee = "";

	private String dueDay = "1";

	private String endDate = "";

	private String bankName = "";

	private String accountNumber = "";

	private String accountHolder = "";

	private String memo = "";

	static LeaseEditForm of(LeaseEdit current) {
		LeaseEditForm form = new LeaseEditForm();
		form.tenantName = current.tenantName().value();
		form.tenantPhone = current.tenantPhone().formatted();
		form.tenantMemo = current.tenantMemo() == null ? "" : current.tenantMemo().value();
		form.deposit = String.valueOf(current.deposit());
		form.firstMonthlyRent = String.valueOf(current.firstMonthlyRent());
		form.firstMaintenanceFee = String.valueOf(current.firstMaintenanceFee());
		form.dueDay = String.valueOf(current.dueDay());
		form.endDate = current.endDate().toString();
		form.bankName = current.bankName();
		form.accountNumber = current.accountNumber().digits();
		form.accountHolder = current.accountHolder().value();
		form.memo = current.memo() == null ? "" : current.memo();
		return form;
	}

	/** @return 오류가 있으면 null */
	LeaseEdit toEdit(BindingResult result, LocalDate startDate) {
		PersonName name = LeaseFields.tenantName(result, tenantName);
		PhoneNumber phone = LeaseFields.phone(result, tenantPhone);
		PrivateMemo tenantMemoValue = LeaseFields.tenantMemo(result, tenantMemo);
		Long depositValue = LeaseFields.deposit(result, deposit);
		Long rent = LeaseFields.rent(result, "firstMonthlyRent", firstMonthlyRent);
		Long fee = LeaseFields.fee(result, "firstMaintenanceFee", firstMaintenanceFee);
		Integer day = LeaseFields.dueDay(result, dueDay);
		LocalDate end = LeaseFields.date(result, "endDate", endDate, "나가는 날을 입력해 주세요");
		if (end != null && !end.isAfter(startDate)) {
			result.rejectValue("endDate", "beforeStart", "나가는 날은 들어오는 날보다 뒤여야 해요");
		}
		String bank = LeaseFields.bankName(result, bankName);
		AccountNumber account = LeaseFields.account(result, accountNumber);
		PersonName holder = LeaseFields.accountHolder(result, accountHolder);
		String leaseMemo = LeaseFields.plainMemo(result, "memo", memo, LeaseTerms.MEMO_MAX_LENGTH);
		if (result.hasErrors()) {
			return null;
		}
		return new LeaseEdit(name, phone, tenantMemoValue, depositValue, day, end, bank, account, holder, leaseMemo,
				rent, fee);
	}

	public String getTenantName() {
		return tenantName;
	}

	public void setTenantName(String tenantName) {
		this.tenantName = tenantName;
	}

	public String getTenantPhone() {
		return tenantPhone;
	}

	public void setTenantPhone(String tenantPhone) {
		this.tenantPhone = tenantPhone;
	}

	public String getTenantMemo() {
		return tenantMemo;
	}

	public void setTenantMemo(String tenantMemo) {
		this.tenantMemo = tenantMemo;
	}

	public String getDeposit() {
		return deposit;
	}

	public void setDeposit(String deposit) {
		this.deposit = deposit;
	}

	public String getFirstMonthlyRent() {
		return firstMonthlyRent;
	}

	public void setFirstMonthlyRent(String firstMonthlyRent) {
		this.firstMonthlyRent = firstMonthlyRent;
	}

	public String getFirstMaintenanceFee() {
		return firstMaintenanceFee;
	}

	public void setFirstMaintenanceFee(String firstMaintenanceFee) {
		this.firstMaintenanceFee = firstMaintenanceFee;
	}

	public String getDueDay() {
		return dueDay;
	}

	public void setDueDay(String dueDay) {
		this.dueDay = dueDay;
	}

	public String getEndDate() {
		return endDate;
	}

	public void setEndDate(String endDate) {
		this.endDate = endDate;
	}

	public String getBankName() {
		return bankName;
	}

	public void setBankName(String bankName) {
		this.bankName = bankName;
	}

	public String getAccountNumber() {
		return accountNumber;
	}

	public void setAccountNumber(String accountNumber) {
		this.accountNumber = accountNumber;
	}

	public String getAccountHolder() {
		return accountHolder;
	}

	public void setAccountHolder(String accountHolder) {
		this.accountHolder = accountHolder;
	}

	public String getMemo() {
		return memo;
	}

	public void setMemo(String memo) {
		this.memo = memo;
	}

	// 입력값(개인정보)이 로그에 남지 않게 한다
	@Override
	public String toString() {
		return "LeaseEditForm[***]";
	}

}
