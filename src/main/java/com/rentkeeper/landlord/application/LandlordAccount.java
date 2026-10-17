package com.rentkeeper.landlord.application;

import com.rentkeeper.landlord.domain.AuthProvider;
import com.rentkeeper.landlord.domain.LandlordStatus;

/**
 * 화면·다른 모듈에 보여 줄 계정 정보 (비밀번호 해시 없음).
 *
 * @param email
 *            연락·알림용 이메일. 카카오 계정은 아직 없을 수 있다(null)
 * @param kakaoId
 *            카카오 회원번호. 이메일 가입 계정은 null
 */
public record LandlordAccount(long id, String email, String name, boolean emailVerified, LandlordStatus status,
		AuthProvider authProvider, String kakaoId) {

	public boolean isKakao() {
		return authProvider == AuthProvider.KAKAO;
	}

}
