package com.rentkeeper.common.crypto;

/**
 * 암호화 키 설정이 없거나 잘못됐다. 앱이 뜨지 않는다 (local 포함, 임시 키를 만들지 않는다: ADR-017). 메시지에는 변수
 * 이름과 고치는 방법만 넣고 키 값은 넣지 않는다.
 */
public class EncryptionKeyException extends IllegalStateException {

	EncryptionKeyException(String message) {
		super(message + " (.env.example의 생성 방법을 참고하세요. 키를 잃으면 세입자 정보를 복구할 수 없습니다, ADR-017)");
	}

}
