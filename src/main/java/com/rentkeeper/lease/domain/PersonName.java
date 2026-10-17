package com.rentkeeper.lease.domain;

import com.rentkeeper.common.crypto.Masking;

/**
 * 사람 이름 (세입자 이름, 예금주). 암호화해 저장한다(ADR-017). 앞뒤 공백을 지우고 안쪽 연속 공백을 하나로, 1~50자(코드
 * 포인트). toString()은 마스킹한다: 로그에 찍혀도 평문이 나가지 않게.
 */
public record PersonName(String value) {

	public static final int MAX_LENGTH = 50;

	public PersonName {
		value = value == null ? "" : value.strip().replaceAll("\\s+", " ");
		if (value.isEmpty() || value.codePointCount(0, value.length()) > MAX_LENGTH) {
			throw new InvalidLeaseValueException("name");
		}
	}

	@Override
	public String toString() {
		return Masking.name(value);
	}

}
