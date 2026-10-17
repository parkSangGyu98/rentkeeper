package com.rentkeeper.auth.settings;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.rentkeeper.landlord.application.LandlordAccount;
import com.rentkeeper.landlord.application.LandlordService;

/**
 * 비밀번호가 있는 일반(LOCAL) 계정만 쓰는 화면(비밀번호 바꾸기, 이메일 바꾸기)을 카카오 계정이 열면 404로 막는다. 카카오
 * 계정에는 확인할 비밀번호가 없다.
 */
@Component
public class LocalAccountGuard {

	private final LandlordService landlordService;

	public LocalAccountGuard(LandlordService landlordService) {
		this.landlordService = landlordService;
	}

	public void requireLocal(long landlordId) {
		if (landlordService.findAccount(landlordId).map(LandlordAccount::isKakao).orElse(true)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
	}

}
