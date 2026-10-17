package com.rentkeeper.lease.domain;

/**
 * 계약·세입자 입력이 규칙에 맞지 않는다. 어느 칸인지(field)만 담고 입력값은 담지 않는다: 메시지가 로그에 남아도 개인정보가 나가지
 * 않게 (ADR-017). 화면은 field에 맞는 안내 문구를 고른다.
 */
public class InvalidLeaseValueException extends IllegalArgumentException {

	private final String field;

	public InvalidLeaseValueException(String field) {
		super("invalid lease value: " + field);
		this.field = field;
	}

	public String field() {
		return field;
	}

}
