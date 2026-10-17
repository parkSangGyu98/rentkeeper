package com.rentkeeper.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 검색·중복 확인용 HMAC-SHA256 (ADR-017). 암호문은 매번 달라 DB에서 비교할 수 없으므로, 정규화한 값의 HMAC을 별도
 * 컬럼에 둔다.
 * <p>
 * 용도를 접두사로 붙여(예: "phone:01012345678") 다른 용도의 같은 값과 해시가 겹치지 않게 한다. HMAC 키를 바꾸면
 * 모든 해시를 다시 계산해야 하므로(복호화 → 재계산) 교체를 지원하지 않는다.
 */
@Component
public class KeyedHasher {

	private static final String ALGORITHM = "HmacSHA256";

	private final SecretKeySpec key;

	public KeyedHasher(EncryptionProperties properties) {
		String base64 = properties.hmacKey();
		if (!StringUtils.hasText(base64)) {
			throw new EncryptionKeyException("APP_HMAC_KEY가 없습니다. local에서도 필요합니다");
		}
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(base64.strip());
		} catch (IllegalArgumentException e) {
			throw new EncryptionKeyException("APP_HMAC_KEY가 Base64 형식이 아닙니다");
		}
		if (bytes.length < FieldEncryptor.KEY_BYTES) {
			throw new EncryptionKeyException("APP_HMAC_KEY는 32바이트 이상이어야 합니다 (지금 " + bytes.length + "바이트)");
		}
		this.key = new SecretKeySpec(bytes, ALGORITHM);
	}

	/** @return 소문자 hex 64자 */
	public String hash(String purpose, String normalizedValue) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(key);
			byte[] digest = mac.doFinal((purpose + ":" + normalizedValue).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (GeneralSecurityException e) {
			throw new FieldEncryptionException("hmac failed: purpose=" + purpose, e);
		}
	}

}
