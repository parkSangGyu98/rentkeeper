package com.rentkeeper.lease.domain;

/**
 * 계약 상태 (V14 ck_lease_status). 전이는 ACTIVE → ENDED 한 방향뿐이다 (Lease.end, M2 커밋 5)
 */
public enum LeaseStatus {

	/** 진행 중. 나가는 날이 지나도 종료 처리 전까지는 ACTIVE다 */
	ACTIVE,

	/** 종료 처리함. 이후 청구를 만들지 않는다(LEASE-3). 겹침 검사에서 빠진다 */
	ENDED

}
