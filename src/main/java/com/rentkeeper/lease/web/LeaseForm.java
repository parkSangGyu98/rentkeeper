package com.rentkeeper.lease.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.springframework.validation.BindingResult;

import com.rentkeeper.common.money.Won;
import com.rentkeeper.lease.application.LeaseViews.AccountPrefill;
import com.rentkeeper.lease.application.LeaseViews.LeaseRegistration;
import com.rentkeeper.lease.domain.AccountNumber;
import com.rentkeeper.lease.domain.InvalidLeaseValueException;
import com.rentkeeper.lease.domain.LeaseRentHistory;
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
		PersonName name = field(result, "tenantName", "세입자 이름을 1~50자로 입력해 주세요", () -> new PersonName(tenantName));
		PhoneNumber phone = field(result, "tenantPhone", "휴대폰 번호를 다시 확인해 주세요 (예: 010-1234-5678)",
				() -> PhoneNumber.parse(tenantPhone));
		PrivateMemo tenantMemoValue = field(result, "tenantMemo", "메모는 500자 이하로 입력해 주세요",
				() -> PrivateMemo.ofNullable(tenantMemo));

		Long depositValue = amount(result, "deposit", deposit, false, LeaseTerms.DEPOSIT_MAX,
				"보증금을 숫자로 입력해 주세요 (없으면 0)", "보증금은 100억 원 이하로 입력해 주세요");
		Long rent = amount(result, "monthlyRent", monthlyRent, false, LeaseRentHistory.AMOUNT_MAX,
				"월세를 숫자로 입력해 주세요 (예: 500000)", "월세는 1억 원 이하로 입력해 주세요");
		Long fee = amount(result, "maintenanceFee", maintenanceFee, true, LeaseRentHistory.AMOUNT_MAX,
				"관리비를 숫자로 입력해 주세요 (없으면 비워 두세요)", "관리비는 1억 원 이하로 입력해 주세요");
		Integer day = dueDay == null || !dueDay.strip().matches("[0-9]{1,2}") ? null : Integer.valueOf(dueDay.strip());
		if (day == null || day < 1 || day > 31) {
			result.rejectValue("dueDay", "invalid", "매달 받는 날을 1~31일 중에서 골라 주세요");
		}

		LocalDate start = date(result, "startDate", startDate, "들어오는 날을 입력해 주세요");
		LocalDate end = date(result, "endDate", endDate, "나가는 날을 입력해 주세요");
		if (start != null && end != null && !end.isAfter(start)) {
			result.rejectValue("endDate", "beforeStart", "나가는 날은 들어오는 날보다 뒤여야 해요");
			end = null;
		}

		String bank = bankName == null ? "" : bankName.strip();
		if (bank.isEmpty() || bank.codePointCount(0, bank.length()) > LeaseTerms.BANK_NAME_MAX_LENGTH) {
			result.rejectValue("bankName", "invalid", "은행 이름을 1~50자로 입력해 주세요 (예: 국민은행)");
		}
		AccountNumber account = field(result, "accountNumber", "계좌번호를 숫자로 입력해 주세요 (6~30자리, - 는 빼도 돼요)",
				() -> AccountNumber.parse(accountNumber));
		PersonName holder = field(result, "accountHolder", "예금주 이름을 1~50자로 입력해 주세요",
				() -> new PersonName(accountHolder));
		String leaseMemo = memo == null ? "" : memo.strip();
		if (leaseMemo.codePointCount(0, leaseMemo.length()) > LeaseTerms.MEMO_MAX_LENGTH) {
			result.rejectValue("memo", "tooLong", "메모는 500자 이하로 입력해 주세요");
		}

		if (result.hasErrors()) {
			return null;
		}
		LeaseTerms terms = new LeaseTerms(depositValue, day, start, end, bank, account, holder, leaseMemo);
		return new LeaseRegistration(unitId, name, phone, tenantMemoValue, rent, fee, terms, useExistingTenant);
	}

	private interface Parser<T> {

		T parse();

	}

	private static <T> T field(BindingResult result, String field, String message, Parser<T> parser) {
		try {
			return parser.parse();
		} catch (InvalidLeaseValueException e) {
			result.rejectValue(field, "invalid", message);
			return null;
		}
	}

	private static Long amount(BindingResult result, String field, String raw, boolean blankIsZero, long max,
			String invalidMessage, String tooLargeMessage) {
		if (blankIsZero && (raw == null || raw.isBlank())) {
			return 0L;
		}
		Long value = Won.parse(raw);
		if (value == null) {
			result.rejectValue(field, "invalid", invalidMessage);
			return null;
		}
		if (value > max) {
			result.rejectValue(field, "tooLarge", tooLargeMessage);
			return null;
		}
		return value;
	}

	private static LocalDate date(BindingResult result, String field, String raw, String message) {
		try {
			return raw == null || raw.isBlank() ? reject(result, field, message) : LocalDate.parse(raw.strip());
		} catch (DateTimeParseException e) {
			return reject(result, field, message);
		}
	}

	private static LocalDate reject(BindingResult result, String field, String message) {
		result.rejectValue(field, "invalid", message);
		return null;
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
