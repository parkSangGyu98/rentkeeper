package com.rentkeeper.billing.domain;

/**
 * 청구 상태 규칙 위반: 전이표에 없는 전이, 입금이 있는 청구 취소, 취소한 청구 수정 등 (ADR-023). 화면에서 막지 못한 경우의
 * 마지막 방어선이라 사용자 문구 대신 이유 코드만 담는다.
 */
public class ChargeStateException extends IllegalStateException {

	public ChargeStateException(String reason) {
		super(reason);
	}

}
