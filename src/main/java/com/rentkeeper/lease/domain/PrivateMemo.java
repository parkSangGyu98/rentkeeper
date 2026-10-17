package com.rentkeeper.lease.domain;

import com.rentkeeper.common.crypto.Masking;

/**
 * 암호화해 저장하는 메모 (세입자 메모, M2 결정 9). 줄바꿈은 살리고 앞뒤 공백만 지운다. 500자(코드 포인트)까지.
 * toString()은 길이만.
 */
public record PrivateMemo(String value) {

	public static final int MAX_LENGTH = 500;

	/** @return 비었으면 null */
	public static PrivateMemo ofNullable(String raw) {
		return raw == null || raw.isBlank() ? null : new PrivateMemo(raw);
	}

	public PrivateMemo {
		value = value == null ? "" : value.strip();
		if (value.isEmpty() || value.codePointCount(0, value.length()) > MAX_LENGTH) {
			throw new InvalidLeaseValueException("memo");
		}
	}

	@Override
	public String toString() {
		return Masking.text(value);
	}

}
