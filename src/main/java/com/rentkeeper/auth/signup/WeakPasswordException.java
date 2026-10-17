package com.rentkeeper.auth.signup;

import com.rentkeeper.common.error.BusinessException;
import com.rentkeeper.common.error.ErrorCode;

public class WeakPasswordException extends BusinessException {

	private final PasswordPolicy.Violation violation;

	public WeakPasswordException(PasswordPolicy.Violation violation) {
		// 메시지에 비밀번호 자체는 절대 넣지 않는다
		super(ErrorCode.WEAK_PASSWORD, "password rejected: " + violation);
		this.violation = violation;
	}

	public PasswordPolicy.Violation getViolation() {
		return violation;
	}

}
