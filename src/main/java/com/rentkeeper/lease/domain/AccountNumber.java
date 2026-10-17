package com.rentkeeper.lease.domain;

import java.util.regex.Pattern;

import com.rentkeeper.common.crypto.Masking;

/**
 * 월세 받을 계좌번호. 하이픈·공백을 지우고 숫자 6~30자리만 저장한다. 암호화해 저장한다(ADR-017). toString()은 마지막
 * 4자리만.
 */
public record AccountNumber(String digits) {

	public static final int MAX_DIGITS = 30;

	private static final Pattern DIGITS = Pattern.compile("[0-9]{6," + MAX_DIGITS + "}");

	public static AccountNumber parse(String raw) {
		return new AccountNumber(raw == null ? "" : raw.replaceAll("[\\s\\-]", ""));
	}

	public AccountNumber {
		if (digits == null || !DIGITS.matcher(digits).matches()) {
			throw new InvalidLeaseValueException("accountNumber");
		}
	}

	@Override
	public String toString() {
		return Masking.account(digits);
	}

}
