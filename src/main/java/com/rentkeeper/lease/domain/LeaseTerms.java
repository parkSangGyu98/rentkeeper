package com.rentkeeper.lease.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 계약 조건 (등록·수정 입력). 만들 때 규칙을 검사한다. 월세·관리비는 이력(LeaseRentHistory)에 따로 둔다(M2 결정
 * 5).
 *
 * @param deposit
 *            보증금(원), 0 이상 100억 이하 (M2 결정 8)
 * @param dueDay
 *            매달 받는 날 1~31. 없는 날은 그 달 말일로 보정한다(계산은 M3)
 * @param startDate
 *            들어오는 날 (포함)
 * @param endDate
 *            나가는 날. 겹침 검사에서는 빼고 본다([) 범위, M2 결정 2). 들어오는 날보다 뒤
 * @param memo
 *            평문 메모, 500자까지
 */
public record LeaseTerms(long deposit, int dueDay, LocalDate startDate, LocalDate endDate, String bankName,
		AccountNumber accountNumber, PersonName accountHolder, String memo) {

	public static final long DEPOSIT_MAX = 10_000_000_000L;

	public static final int BANK_NAME_MAX_LENGTH = 50;

	public static final int MEMO_MAX_LENGTH = 500;

	public LeaseTerms {
		if (deposit < 0 || deposit > DEPOSIT_MAX) {
			throw new InvalidLeaseValueException("deposit");
		}
		if (dueDay < 1 || dueDay > 31) {
			throw new InvalidLeaseValueException("dueDay");
		}
		if (startDate == null) {
			throw new InvalidLeaseValueException("startDate");
		}
		if (endDate == null || !endDate.isAfter(startDate)) {
			throw new InvalidLeaseValueException("endDate");
		}
		bankName = bankName == null ? "" : bankName.strip().replaceAll("\\s+", " ");
		if (bankName.isEmpty() || bankName.codePointCount(0, bankName.length()) > BANK_NAME_MAX_LENGTH) {
			throw new InvalidLeaseValueException("bankName");
		}
		Objects.requireNonNull(accountNumber, "accountNumber");
		Objects.requireNonNull(accountHolder, "accountHolder");
		memo = memo == null || memo.isBlank() ? null : memo.strip();
		if (memo != null && memo.codePointCount(0, memo.length()) > MEMO_MAX_LENGTH) {
			throw new InvalidLeaseValueException("memo");
		}
	}

}
