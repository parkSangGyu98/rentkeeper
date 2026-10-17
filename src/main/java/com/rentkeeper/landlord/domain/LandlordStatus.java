package com.rentkeeper.landlord.domain;

public enum LandlordStatus {

	ACTIVE,

	/** 탈퇴 처리됨. 30일 뒤 개인정보 파기 배치 대상 (M1-c) */
	WITHDRAWN,

}
