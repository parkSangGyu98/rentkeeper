package com.rentkeeper.billing.domain;

/** 청구를 만든 방법 */
public enum ChargeSource {

	/** 청구 생성 배치 */
	AUTO,

	/** 임대인이 직접 만듦 (M3-b) */
	MANUAL

}
