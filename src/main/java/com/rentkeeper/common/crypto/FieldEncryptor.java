package com.rentkeeper.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 개인정보 컬럼 암호화 (AES-256-GCM, ADR-017).
 * <p>
 * 저장 형식: {@code v{키버전}:{base64url(IV 12바이트 ‖ 암호문 ‖ 인증 태그 16바이트)}}. 같은 평문도 매번 다른
 * IV를 써서 다른 암호문이 된다(그래서 DB에서 값으로 검색할 수 없다. 중복 확인은 {@link KeyedHasher}).
 * 용도(purpose, 예: tenant.phone)를 AAD로 묶어, 암호문을 다른 컬럼에 옮겨 붙이면 복호화가 실패한다.
 * <p>
 * 현재 키로 암호화하고, 복호화는 암호문의 버전에 맞는 키(현재 또는 APP_ENCRYPTION_PREVIOUS_KEYS)로 한다. 키 교체
 * 중에도 옛 값을 읽을 수 있다.
 */
@Component
public class FieldEncryptor {

	static final int KEY_BYTES = 32;

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";

	private static final int IV_BYTES = 12;

	private static final int TAG_BITS = 128;

	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

	private final SecureRandom random = new SecureRandom();

	private final int currentVersion;

	private final Map<Integer, SecretKey> keys;

	public FieldEncryptor(EncryptionProperties properties) {
		this.currentVersion = parseVersion(properties.keyVersion(), "APP_ENCRYPTION_KEY_VERSION");
		Map<Integer, SecretKey> parsed = new HashMap<>();
		parsed.put(currentVersion, parseKey(properties.key(), "APP_ENCRYPTION_KEY"));
		if (StringUtils.hasText(properties.previousKeys())) {
			for (String entry : properties.previousKeys().split(";")) {
				String trimmed = entry.strip();
				if (trimmed.isEmpty()) {
					continue;
				}
				int colon = trimmed.indexOf(':');
				if (colon < 1) {
					throw new EncryptionKeyException(
							"APP_ENCRYPTION_PREVIOUS_KEYS 형식이 잘못됐습니다. \"버전:Base64키\"를 ;로 이어 적습니다");
				}
				int version = parseVersion(trimmed.substring(0, colon), "APP_ENCRYPTION_PREVIOUS_KEYS의 버전");
				if (parsed.containsKey(version)) {
					throw new EncryptionKeyException(
							"APP_ENCRYPTION_PREVIOUS_KEYS에 같은 버전(v" + version + ")이 현재 키나 다른 이전 키와 겹칩니다");
				}
				parsed.put(version,
						parseKey(trimmed.substring(colon + 1), "APP_ENCRYPTION_PREVIOUS_KEYS의 v" + version));
			}
		}
		this.keys = Map.copyOf(parsed);
	}

	/** @return 저장할 문자열. 평문이 null이면 null */
	public String encrypt(String plaintext, String purpose) {
		if (plaintext == null) {
			return null;
		}
		byte[] iv = new byte[IV_BYTES];
		random.nextBytes(iv);
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, keys.get(currentVersion), new GCMParameterSpec(TAG_BITS, iv));
			cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
			byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			byte[] payload = ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
			return "v" + currentVersion + ":" + ENCODER.encodeToString(payload);
		} catch (GeneralSecurityException e) {
			throw new FieldEncryptionException("encryption failed: purpose=" + purpose, e);
		}
	}

	/** @return 평문. 저장값이 null이면 null */
	public String decrypt(String stored, String purpose) {
		if (stored == null) {
			return null;
		}
		int colon = stored.indexOf(':');
		if (!stored.startsWith("v") || colon < 2) {
			throw new FieldEncryptionException("not an encrypted value: purpose=" + purpose);
		}
		int version;
		try {
			version = Integer.parseInt(stored.substring(1, colon));
		} catch (NumberFormatException e) {
			throw new FieldEncryptionException("invalid key version: purpose=" + purpose);
		}
		SecretKey key = keys.get(version);
		if (key == null) {
			throw new FieldEncryptionException("no key for version: purpose=" + purpose + ", version=v" + version
					+ " (APP_ENCRYPTION_PREVIOUS_KEYS 확인)");
		}
		try {
			byte[] payload = DECODER.decode(stored.substring(colon + 1));
			if (payload.length <= IV_BYTES) {
				throw new FieldEncryptionException("truncated value: purpose=" + purpose + ", version=v" + version);
			}
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES));
			cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
			byte[] plain = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
			return new String(plain, StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			// 변조, 다른 컬럼의 암호문, 다른 키로 만든 값 등
			throw new FieldEncryptionException("decryption failed: purpose=" + purpose + ", version=v" + version, e);
		}
	}

	int currentVersion() {
		return currentVersion;
	}

	static SecretKey parseKey(String base64, String variable) {
		if (!StringUtils.hasText(base64)) {
			throw new EncryptionKeyException(variable + "가 없습니다. local에서도 필요합니다");
		}
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(base64.strip());
		} catch (IllegalArgumentException e) {
			throw new EncryptionKeyException(variable + "가 Base64 형식이 아닙니다");
		}
		if (bytes.length != KEY_BYTES) {
			throw new EncryptionKeyException(variable + "는 32바이트여야 합니다 (지금 " + bytes.length + "바이트)");
		}
		return new SecretKeySpec(bytes, "AES");
	}

	static int parseVersion(String value, String variable) {
		if (!StringUtils.hasText(value)) {
			throw new EncryptionKeyException(variable + "가 없습니다 (예: 1)");
		}
		try {
			int version = Integer.parseInt(value.strip());
			if (version < 1) {
				throw new NumberFormatException();
			}
			return version;
		} catch (NumberFormatException e) {
			throw new EncryptionKeyException(variable + "는 1 이상의 정수여야 합니다");
		}
	}

}
