package com.rentkeeper.landlord.application;

import com.rentkeeper.common.error.BusinessException;
import com.rentkeeper.common.error.ErrorCode;

/**
 * 같은 카카오 회원번호의 사용 중인 계정이 이미 있다 (다른 창에서 동시에 가입을 마친 경우 등).
 */
public class DuplicateKakaoAccountException extends BusinessException {

	DuplicateKakaoAccountException(Throwable cause) {
		super(ErrorCode.DUPLICATE_KAKAO_ACCOUNT, "kakao account already registered", cause);
	}

}
