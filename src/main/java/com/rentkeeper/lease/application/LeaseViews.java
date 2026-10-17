package com.rentkeeper.lease.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.rentkeeper.common.money.Won;
import com.rentkeeper.lease.domain.AccountNumber;
import com.rentkeeper.lease.domain.LeaseStatus;
import com.rentkeeper.lease.domain.LeaseTerms;
import com.rentkeeper.lease.domain.PersonName;
import com.rentkeeper.lease.domain.PhoneNumber;
import com.rentkeeper.lease.domain.PrivateMemo;
import com.rentkeeper.property.application.PropertyViews.UnitView;
import com.rentkeeper.property.domain.UnitStatus;

/**
 * 계약 화면에 넘기는 값과 등록 입력. 개인정보는 로그인한 임대인 본인의 화면에만 평문으로 보여 준다. 이 record들의
 * toString()은 값 타입(PersonName 등)의 마스킹을 따르거나 직접 숨긴다.
 */
public final class LeaseViews {

	private LeaseViews() {
	}

	/** 계약 등록 입력 (검증을 마친 값) */
	public record LeaseRegistration(long unitId, PersonName tenantName, PhoneNumber tenantPhone, PrivateMemo tenantMemo,
			long monthlyRent, long maintenanceFee, LeaseTerms terms, boolean useExistingTenant) {

		public String depositText() {
			return Won.format(terms.deposit()) + " (" + Won.korean(terms.deposit()) + ")";
		}

		public String rentText() {
			return Won.format(monthlyRent) + " (" + Won.korean(monthlyRent) + ")";
		}

		public String feeText() {
			return Won.format(maintenanceFee);
		}

		public String dueDayText() {
			return LeaseViews.dueDayText(terms.dueDay());
		}

		@Override
		public String toString() {
			return "LeaseRegistration[unitId=" + unitId + ", tenantName=" + tenantName + ", tenantPhone=" + tenantPhone
					+ "]";
		}

	}

	/** 같은 연락처로 이미 등록된 세입자 (M2 결정 6: "이미 등록된 세입자로 계약할까요?") */
	public record ExistingTenant(String name, String phone) {

		@Override
		public String toString() {
			return "ExistingTenant[***]";
		}

	}

	/** 직전 계약의 입금 계좌 (M2 결정 20) */
	public record AccountPrefill(String bankName, String accountNumber, String accountHolder) {

		static AccountPrefill of(String bankName, AccountNumber number, PersonName holder) {
			return new AccountPrefill(bankName, number.digits(), holder.value());
		}

		@Override
		public String toString() {
			return "AccountPrefill[***]";
		}

	}

	/** 월세 이력 한 줄 */
	/**
	 * @param cancellable
	 *            아직 시작하지 않은 변경이라 취소할 수 있는지 (첫 행은 항상 false)
	 */
	public record RentRow(long id, YearMonth effectiveMonth, long monthlyRent, long maintenanceFee, String reason,
			boolean cancellable) {

		public String monthText() {
			return effectiveMonth.getYear() + "년 " + effectiveMonth.getMonthValue() + "월부터";
		}

		public String rentText() {
			return Won.format(monthlyRent);
		}

		public String rentKorean() {
			return Won.korean(monthlyRent);
		}

		public String feeText() {
			return Won.format(maintenanceFee);
		}

		public String totalText() {
			return Won.format(Math.addExact(monthlyRent, maintenanceFee));
		}

	}

	public record LeaseDetail(long id, UnitView unit, String tenantName, String tenantPhone, String tenantMemo,
			long deposit, int dueDay, LocalDate startDate, LocalDate endDate, LeaseStatus status, LocalDate endedOn,
			String bankName, String accountNumber, String accountHolder, String memo, RentRow currentRent,
			List<RentRow> rentHistory, List<RentRow> upcomingRent) {

		public String depositText() {
			return Won.format(deposit);
		}

		public String depositKorean() {
			return Won.korean(deposit);
		}

		public String dueDayText() {
			return LeaseViews.dueDayText(dueDay);
		}

		public boolean active() {
			return status == LeaseStatus.ACTIVE;
		}

		@Override
		public String toString() {
			return "LeaseDetail[id=" + id + "]";
		}

	}

	/** 호실 화면의 계약 한 줄 */
	public record LeaseRow(long id, String tenantName, LocalDate startDate, LocalDate endDate, LeaseStatus status,
			LocalDate endedOn, boolean current) {

		@Override
		public String toString() {
			return "LeaseRow[id=" + id + "]";
		}

	}

	/** 호실 화면 (/app/units/{id}) */
	public record UnitPage(UnitView unit, UnitStatus status, boolean staleActive, Long currentLeaseId,
			List<LeaseRow> leases) {
	}

	/** "매달 5일", 29일 이상이면 말일 보정 안내 (보정 계산은 M3) */
	public static String dueDayText(int dueDay) {
		if (dueDay == 31) {
			return "매달 31일 (31일이 없는 달은 그 달 마지막 날)";
		}
		if (dueDay >= 29) {
			return "매달 " + dueDay + "일 (2월처럼 " + dueDay + "일이 없는 달은 그 달 마지막 날)";
		}
		return "매달 " + dueDay + "일";
	}

}
