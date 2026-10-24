package com.rentkeeper.lease.domain;

/** 세입자 링크 유형 (V20, M5 결정 7). 계약·유형마다 열린 링크는 하나다. 세입자 화면에서는 똑같이 동작한다 */
public enum TenantLinkPurpose {

	/** 임대인이 계약 상세에서 직접 만들어 보낸 링크 */
	LANDLORD,

	/** 알림(안내 문자)에 넣으려고 보낼 때 만든 링크. 알림을 보낼 때마다 바뀐다 */
	NOTIFICATION

}
