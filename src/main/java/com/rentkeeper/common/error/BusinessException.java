package com.rentkeeper.common.error;

/**
 * 사용자가 고칠 수 있는 업무 규칙 위반. 시스템 오류(DB 장애 등)와 구분해 화면에서 안내 문구로 바꿔 보여 준다.
 */
public abstract class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	protected BusinessException(ErrorCode errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	protected BusinessException(ErrorCode errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}

}
