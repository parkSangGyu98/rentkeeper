package com.rentkeeper.lease.domain;

import java.util.regex.Pattern;

import com.rentkeeper.common.crypto.Masking;

/**
 * 휴대폰 번호 (M2 결정 7: 01X만). 숫자만 남겨 저장한다(PRD 4.3: 010XXXXXXXX). 암호화해 저장하고, 중복 확인은
 * HMAC으로 한다 (ADR-017). toString()은 마스킹한다.
 */
public record PhoneNumber(String digits) {

	public static final int MAX_DIGITS = 11;

	private static final Pattern MOBILE = Pattern.compile("01[016789][0-9]{7,8}");

	/** 하이픈·공백·괄호 등을 지우고 검증한다. 숫자가 아닌 글자(문자 등)가 섞이면 거부 */
	public static PhoneNumber parse(String raw) {
		String digits = raw == null ? "" : raw.replaceAll("[\\s\\-().]", "");
		return new PhoneNumber(digits);
	}

	public PhoneNumber {
		if (digits == null || !MOBILE.matcher(digits).matches()) {
			throw new InvalidLeaseValueException("phone");
		}
	}

	/** 화면 표시용: 11자리 010-1234-5678, 10자리 011-123-4567 */
	public String formatted() {
		int end = digits.length() - 4;
		return digits.substring(0, 3) + "-" + digits.substring(3, end) + "-" + digits.substring(end);
	}

	@Override
	public String toString() {
		return Masking.phone(digits);
	}

}
