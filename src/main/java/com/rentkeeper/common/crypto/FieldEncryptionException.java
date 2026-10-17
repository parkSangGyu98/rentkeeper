package com.rentkeeper.common.crypto;

/**
 * 암호화·복호화 실패. 메시지에는 용도(예: tenant.phone)와 키 버전만 넣는다. 평문·암호문·키는 넣지 않는다 (ADR-017).
 */
public class FieldEncryptionException extends RuntimeException {

	FieldEncryptionException(String message) {
		super(message);
	}

	FieldEncryptionException(String message, Throwable cause) {
		// 원인 예외의 메시지에도 값이 섞일 수 있어 종류만 남긴다
		super(message + " (" + cause.getClass().getSimpleName() + ")");
	}

}
