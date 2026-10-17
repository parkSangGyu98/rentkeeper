package com.rentkeeper.landlord.application;

import com.rentkeeper.common.error.BusinessException;
import com.rentkeeper.common.error.ErrorCode;

public class DuplicateEmailException extends BusinessException {

	DuplicateEmailException() {
		super(ErrorCode.DUPLICATE_EMAIL, "email already registered");
	}

	DuplicateEmailException(Throwable cause) {
		super(ErrorCode.DUPLICATE_EMAIL, "email already registered", cause);
	}

}
