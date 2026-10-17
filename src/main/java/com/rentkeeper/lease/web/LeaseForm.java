package com.rentkeeper.lease.web;

import java.time.LocalDate;

import org.springframework.validation.BindingResult;

import com.rentkeeper.lease.application.LeaseViews.AccountPrefill;
import com.rentkeeper.lease.application.LeaseViews.LeaseRegistration;
import com.rentkeeper.lease.domain.AccountNumber;
import com.rentkeeper.lease.domain.LeaseTerms;
import com.rentkeeper.lease.domain.PersonName;
import com.rentkeeper.lease.domain.PhoneNumber;
import com.rentkeeper.lease.domain.PrivateMemo;

/**
 * 계약 등록 폼 (한 화면 4구역: 1 세입자 → 2 금액 → 3 기간 → 4 입금 계좌, M2 계획). 모든 칸을 문자열로 받아 칸마다
 * 알아보기 쉬운 안내 문구로 검증한다. 안내 문구에는 입력값을 넣지 않는다.
 * <p>
 * 확인 화면은 같은 값을 숨은 칸으로 다시 보낸다(세션에 평문 개인정보를 저장하지 않으려고: spring_session_attributes는
 * 암호화되지 않는다). 저장할 때 다시 검증한다.
 * <p>
 * 레코드가 아니라 일반 객체(게터·세터)다: 칸별 오류를 {@code rejectValue}로 직접 넣는데, 생성자로 바인딩한 레코드는
 * BindingResult가 대상 객체를 갖지 않아 rejectValue가 실패한다.
 */
public class LeaseForm {

	private Long unitId;

	private String tenantName = "";

	private String tenantPhone = "";

	private String tenantMemo = "";

	private String deposit = "";

	private String monthlyRent = "";

	private String maintenanceFee = "";

	private String dueDay = "1";

	private String startDate = "";

	private String endDate = "";

	private String bankName = "";

	private String accountNumber = "";

	private String accountHolder = "";

	private String memo = "";

	private boolean useExistingTenant;

	static LeaseForm empty(long unitId, AccountPrefill prefill) {
		LeaseForm form = new LeaseForm();
		form.unitId = unitId;
		if (prefill != null) {
			form.bankName = prefill.bankName();
			form.accountNumber = prefill.accountNumber();
			form.accountHolder = prefill.accountHolder();
		}
		return form;
	}

	/**
	 * 칸마다 검증하고, 모두 맞으면 등록 입력을 만든다.
	 *
	 * @return 오류가 하나라도 있으면 null (오류는 result에 칸별로 넣는다)
	 */
	LeaseRegistration toRegistration(BindingResult result) {
		PersonName name = LeaseFields.tenantName(result, tenantName);
		PhoneNumber phone = LeaseFields.phone(result, tenantPhone);
		PrivateMemo tenantMemoValue = LeaseFields.tenantMemo(result, tenantMemo);
		Long depositValue = LeaseFields.deposit(result, deposit);
		Long rent = LeaseFields.rent(result, "monthlyRent", monthlyRent);
		Long fee = LeaseFields.fee(result, "maintenanceFee", maintenanceFee);
		Integer day = LeaseFields.dueDay(result, dueDay);
		LocalDate start = LeaseFields.date(result, "startDate", startDate, "들어오는 날을 입력해 주세요");
		LocalDate end = LeaseFields.date(result, "endDate", endDate, "나가는 날을 입력해 주세요");
		if (start != null && end != null && !end.isAfter(start)) {
			result.rejectValue("endDate", "beforeStart", "나가는 날은 들어오는 날보다 뒤여야 해요");
		}
		String bank = LeaseFields.bankName(result, bankName);
		AccountNumber account = LeaseFields.account(result, accountNumber);
		PersonName holder = LeaseFields.accountHolder(result, accountHolder);
		String leaseMemo = LeaseFields.plainMemo(result, "memo", memo, LeaseTerms.MEMO_MAX_LENGTH);

		if (result.hasErrors()) {
			return null;
		}
		LeaseTerms terms = new LeaseTerms(depositValue, day, start, end, bank, account, holder, leaseMemo);
		return new LeaseRegistration(unitId, name, phone, tenantMemoValue, rent, fee, terms, useExistingTenant);
	}

	public Long getUnitId() {
		return unitId;
	}

	public void setUnitId(Long unitId) {
		this.unitId = unitId;
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

	public String getDueDay() {
		return dueDay;
	}

	public void setDueDay(String dueDay) {
		this.dueDay = dueDay;
	}

	public String getStartDate() {
		return startDate;
	}

	public void setStartDate(String startDate) {
		this.startDate = startDate;
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

	public boolean isUseExistingTenant() {
		return useExistingTenant;
	}

	public void setUseExistingTenant(boolean useExistingTenant) {
		this.useExistingTenant = useExistingTenant;
	}

	// 입력값(개인정보)이 로그에 남지 않게 한다
	@Override
	public String toString() {
		return "LeaseForm[unitId=" + unitId + "]";
	}

}
