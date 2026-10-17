package com.rentkeeper.landlord.application;

import com.rentkeeper.landlord.domain.LandlordStatus;

/**
 * 로그인 검증에 필요한 최소 정보. 엔티티를 모듈 밖(auth)으로 내보내지 않기 위한 전달 객체다.
 */
public record LandlordCredentials(long id, String email, String passwordHash, String name, LandlordStatus status) {

	// 해시라도 로그에 남기지 않는다
	@Override
	public String toString() {
		return "LandlordCredentials[id=" + id + ", status=" + status + "]";
	}

}
