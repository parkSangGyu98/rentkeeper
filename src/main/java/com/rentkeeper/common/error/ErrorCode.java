package com.rentkeeper.common.error;

/**
 * 도메인 예외의 에러 코드 (PRD 11장). 화면 문구는 화면 계층에서 코드에 맞춰 고르고, 예외 메시지는 내부 로그용으로만 쓴다.
 */
public enum ErrorCode {

	DUPLICATE_EMAIL,

	DUPLICATE_KAKAO_ACCOUNT,

	INVALID_EMAIL,

	INVALID_NAME,

	WEAK_PASSWORD,

	DUPLICATE_PROPERTY_NAME,

	DUPLICATE_UNIT_NAME,

	PROPERTY_HAS_UNITS,

	UNIT_HAS_LEASES,

	LEASE_PERIOD_OVERLAP,

	EXISTING_TENANT,

	LEASE_ENDED,

	LEASE_HAS_CHARGES,

	FIRST_MONTH_CHARGED,

}
