package com.rentkeeper.landlord.domain;

/**
 * 계정을 만든 인증 방식. 같은 이메일이어도 공급자가 다르면 별도 계정이다 (자동 병합 금지, ADR-006).
 */
public enum AuthProvider {

	/** 이메일 + 비밀번호 */
	LOCAL,

	/** 카카오 로그인 (M1-c) */
	KAKAO,

}
