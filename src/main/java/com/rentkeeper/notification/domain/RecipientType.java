package com.rentkeeper.notification.domain;

/** 받는 사람 종류 */
public enum RecipientType {

	/** 세입자 (tenant_id) */
	TENANT,

	/** 임대인 본인 (landlord_id) */
	LANDLORD,

	/** 운영자 (OPS_ALERT_EMAIL) */
	OPS

}
