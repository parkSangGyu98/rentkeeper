package com.rentkeeper.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 개인정보 암호화 키 (ADR-017). 값은 환경변수(로컬은 .env)로만 받는다. 키 값은 로그·예외 메시지에 넣지 않는다.
 *
 * @param key
 *            APP_ENCRYPTION_KEY. Base64로 적은 32바이트(AES-256) 현재 키
 * @param keyVersion
 *            APP_ENCRYPTION_KEY_VERSION. 현재 키의 버전(양의 정수). 암호문 앞에 "v버전:"으로 들어간다
 * @param previousKeys
 *            APP_ENCRYPTION_PREVIOUS_KEYS. 키 교체 중에만 쓰는 이전 키들
 *            "1:Base64;2:Base64" (복호화 전용, 비워도 됨)
 * @param hmacKey
 *            APP_HMAC_KEY. 전화번호 중복 확인용 HMAC 키, Base64로 적은 32바이트 이상
 */
@ConfigurationProperties("app.encryption")
public record EncryptionProperties(String key, String keyVersion, String previousKeys, String hmacKey) {

	// 키 값이 로그·예외 메시지에 찍히지 않게 한다
	@Override
	public String toString() {
		return "EncryptionProperties[keyVersion=" + keyVersion + "]";
	}

}
