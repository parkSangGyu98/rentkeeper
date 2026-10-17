package com.rentkeeper.billing.domain;

import java.time.LocalDate;
import java.time.YearMonth;

/** 청구 날짜 계산 (ADR-024). 모두 서울 기준 날짜다 */
public final class ChargeCalendar {

	private ChargeCalendar() {
	}

	/**
	 * 그 달의 납부일. 받는 날이 그 달에 없으면(31일의 4월, 29~31일의 2월) 그 달 마지막 날로 보정한다 (PRD 12장 필수 2).
	 *
	 * @param dueDay
	 *            계약의 받는 날 (1~31)
	 */
	public static LocalDate dueDate(YearMonth month, int dueDay) {
		if (dueDay < 1 || dueDay > 31) {
			throw new IllegalArgumentException("dueDay must be 1~31");
		}
		return month.atDay(Math.min(dueDay, month.lengthOfMonth()));
	}

	/** 그 달 청구를 만들 날 = 납부일 − issueDaysBefore (0~28, M3 결정 17). 앞 달로 넘어갈 수 있다 */
	public static LocalDate issueDate(YearMonth month, int dueDay, int issueDaysBefore) {
		if (issueDaysBefore < 0 || issueDaysBefore > 28) {
			throw new IllegalArgumentException("issueDaysBefore must be 0~28");
		}
		return dueDate(month, dueDay).minusDays(issueDaysBefore);
	}

}
