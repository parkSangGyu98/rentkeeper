package com.rentkeeper.auth.signup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 운영(prod)에서는 초대 코드가 있어야 기동한다 (M6 결정 3). 코드가 비면 누구나 가입할 수 있게 되므로, 설정을 빠뜨린 채
 * 공개되는 것보다 기동 실패가 낫다. 짐작하기 어렵게 16자 이상만 받는다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
class ProdInviteCodeGuard {

	static final int MIN_LENGTH = 16;

	ProdInviteCodeGuard(@Value("${app.signup.invite-code:}") String inviteCode) {
		if (inviteCode == null || inviteCode.strip().length() < MIN_LENGTH) {
			throw new IllegalStateException(
					"운영(prod)에는 " + MIN_LENGTH + "자 이상의 초대 코드(APP_SIGNUP_INVITE_CODE)가 필요합니다 (M6 결정 3).");
		}
	}

}
