package com.rentkeeper.landlord.application;

import com.rentkeeper.landlord.domain.LandlordStatus;

/**
 * 화면·다른 모듈에 보여 줄 계정 정보 (비밀번호 해시 없음).
 */
public record LandlordAccount(long id, String email, String name, boolean emailVerified, LandlordStatus status) {
}
