package com.rentkeeper.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

/**
 * AES-256-GCM 필드 암호화 (ADR-017): 왕복, 매번 다른 암호문, 용도(AAD) 묶기, 변조 감지, 키 교체 중 옛 키로
 * 읽기, 키 설정 검증, 오류 메시지에 평문·키가 없음.
 */
class FieldEncryptorTest {

	static final String KEY_V1 = key("rentkeeper-test-only-enc-key-v1!");

	static final String KEY_V2 = key("rentkeeper-test-only-enc-key-v2!");

	private static final String PLAIN = "홍길동 01012345678";

	@Test
	void roundTripsAndUsesFreshIvEachTime() {
		FieldEncryptor encryptor = encryptor(KEY_V1, "1", "");

		String first = encryptor.encrypt(PLAIN, "tenant.name");
		String second = encryptor.encrypt(PLAIN, "tenant.name");

		assertThat(first).startsWith("v1:").doesNotContain("홍길동").doesNotContain("01012345678");
		assertThat(first).as("같은 평문도 매번 다른 암호문").isNotEqualTo(second);
		assertThat(encryptor.decrypt(first, "tenant.name")).isEqualTo(PLAIN);
		assertThat(encryptor.encrypt(null, "tenant.name")).isNull();
		assertThat(encryptor.decrypt(null, "tenant.name")).isNull();
	}

	@Test
	void ciphertextIsBoundToPurpose() {
		FieldEncryptor encryptor = encryptor(KEY_V1, "1", "");
		String phone = encryptor.encrypt(PLAIN, "tenant.phone");

		assertThatThrownBy(() -> encryptor.decrypt(phone, "tenant.name")).as("다른 컬럼에 옮겨 붙인 암호문")
				.isInstanceOf(FieldEncryptionException.class).hasMessageContaining("purpose=tenant.name").message()
				.doesNotContain(PLAIN).doesNotContain(phone);
	}

	@Test
	void detectsTampering() {
		FieldEncryptor encryptor = encryptor(KEY_V1, "1", "");
		String stored = encryptor.encrypt(PLAIN, "tenant.name");
		// 가운데 글자를 바꾼다. 패딩 없는 base64url의 마지막 글자는 쓰지 않는 비트가 있어 바꿔도 같은 바이트로 풀릴 수 있다
		int at = stored.length() / 2;
		char original = stored.charAt(at);
		String tampered = stored.substring(0, at) + (original == 'A' ? 'B' : 'A') + stored.substring(at + 1);

		assertThatThrownBy(() -> encryptor.decrypt(tampered, "tenant.name"))
				.isInstanceOf(FieldEncryptionException.class);
		assertThatThrownBy(() -> encryptor.decrypt("plain text", "tenant.name"))
				.isInstanceOf(FieldEncryptionException.class).message().doesNotContain("plain text");
		assertThatThrownBy(() -> encryptor.decrypt("v1:", "tenant.name")).isInstanceOf(FieldEncryptionException.class);
	}

	@Test
	void readsOldValuesDuringKeyRotation() {
		String oldValue = encryptor(KEY_V1, "1", "").encrypt(PLAIN, "tenant.name");

		FieldEncryptor rotated = encryptor(KEY_V2, "2", "1:" + KEY_V1);

		assertThat(rotated.decrypt(oldValue, "tenant.name")).as("옛 키(v1) 값도 읽힌다").isEqualTo(PLAIN);
		assertThat(rotated.encrypt(PLAIN, "tenant.name")).as("새 값은 현재 키(v2)로").startsWith("v2:");

		FieldEncryptor withoutOldKey = encryptor(KEY_V2, "2", "");
		assertThatThrownBy(() -> withoutOldKey.decrypt(oldValue, "tenant.name"))
				.isInstanceOf(FieldEncryptionException.class).hasMessageContaining("v1")
				.hasMessageContaining("APP_ENCRYPTION_PREVIOUS_KEYS");
	}

	@Test
	void rejectsMissingOrInvalidKeysWithoutPrintingThem() {
		assertThatThrownBy(() -> encryptor("", "1", "")).isInstanceOf(EncryptionKeyException.class)
				.hasMessageContaining("APP_ENCRYPTION_KEY가 없습니다").hasMessageContaining("복구할 수 없습니다");
		String shortKey = key("too-short");
		assertThatThrownBy(() -> encryptor(shortKey, "1", "")).hasMessageContaining("32바이트").message()
				.doesNotContain(shortKey);
		assertThatThrownBy(() -> encryptor("not base64!!", "1", "")).hasMessageContaining("Base64");
		assertThatThrownBy(() -> encryptor(KEY_V1, "", "")).hasMessageContaining("APP_ENCRYPTION_KEY_VERSION");
		assertThatThrownBy(() -> encryptor(KEY_V1, "0", "")).hasMessageContaining("1 이상");
		assertThatThrownBy(() -> encryptor(KEY_V1, "1", "1:" + KEY_V2)).hasMessageContaining("겹칩니다").message()
				.doesNotContain(KEY_V2);
		assertThatThrownBy(() -> encryptor(KEY_V1, "2", "garbage")).hasMessageContaining("형식");
	}

	@Test
	void propertiesToStringHidesKeys() {
		assertThat(new EncryptionProperties(KEY_V1, "1", "1:" + KEY_V2, KEY_V2).toString()).doesNotContain(KEY_V1)
				.doesNotContain(KEY_V2);
	}

	static FieldEncryptor encryptor(String key, String version, String previous) {
		return new FieldEncryptor(new EncryptionProperties(key, version, previous, KEY_V2));
	}

	static String key(String text) {
		return Base64.getEncoder().encodeToString(text.getBytes());
	}

}
