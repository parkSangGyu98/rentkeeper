package com.rentkeeper.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KeyedHasherAndMaskingTest {

	private final KeyedHasher hasher = new KeyedHasher(
			new EncryptionProperties(null, null, null, FieldEncryptorTest.key("rentkeeper-test-only-hmac-key-01")));

	@Test
	void hmacIsDeterministicHexAndSeparatedByPurpose() {
		String hash = hasher.hash("phone", "01012345678");

		assertThat(hash).matches("[0-9a-f]{64}").isEqualTo(hasher.hash("phone", "01012345678"));
		assertThat(hasher.hash("phone", "01012345679")).isNotEqualTo(hash);
		assertThat(hasher.hash("other", "01012345678")).as("용도가 다르면 다른 해시").isNotEqualTo(hash);
	}

	@Test
	void hmacKeyIsRequired() {
		assertThatThrownBy(() -> new KeyedHasher(new EncryptionProperties(null, null, null, "")))
				.isInstanceOf(EncryptionKeyException.class).hasMessageContaining("APP_HMAC_KEY");
		assertThatThrownBy(
				() -> new KeyedHasher(new EncryptionProperties(null, null, null, FieldEncryptorTest.key("short"))))
				.hasMessageContaining("32바이트 이상");
	}

	@Test
	void masksPersonalInfo() {
		assertThat(Masking.name("김철수")).isEqualTo("김*수");
		assertThat(Masking.name("김철")).isEqualTo("김*");
		assertThat(Masking.name("남궁민수")).isEqualTo("남**수");
		assertThat(Masking.name("김")).isEqualTo("*");
		assertThat(Masking.phone("01012345678")).isEqualTo("010-****-5678");
		assertThat(Masking.account("12345678901")).isEqualTo("****8901");
		assertThat(Masking.text("주민번호 적지 마세요")).isEqualTo("[11자]");
	}

}
