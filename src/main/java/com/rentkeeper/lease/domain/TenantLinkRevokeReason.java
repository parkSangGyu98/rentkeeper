package com.rentkeeper.lease.domain;

/** 링크를 닫은 이유 (V20). 세입자 화면은 이유와 관계없이 같은 "링크를 열 수 없어요"다 */
public enum TenantLinkRevokeReason {

	/** 임대인이 [링크 닫기]를 누름 */
	LANDLORD_CLOSED,

	/** 임대인이 [새 링크 만들기]로 바꿈 */
	LANDLORD_REISSUED,

	/** 새 알림을 보내며 예전 알림 링크를 닫음 */
	NOTIFICATION_ROTATED,

	/** V20 전에 닫혀 이유가 기록되지 않음 (앱은 이 값을 쓰지 않는다) */
	UNKNOWN

}
