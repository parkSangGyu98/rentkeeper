package com.rentkeeper.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SecureTokensTest {

	@Test
	void newTokenIsUrlSafe43CharactersAndUnique() {
		Set<String> tokens = new HashSet<>();
		for (int i = 0; i < 1000; i++) {
			String token = SecureTokens.newToken();
			assertThat(SecureTokens.isWellFormed(token)).isTrue();
			tokens.add(token);
		}
		assertThat(tokens).hasSize(1000);
	}

	@Test
	void rejectsMalformedTokens() {
		assertThat(SecureTokens.isWellFormed(null)).isFalse();
		assertThat(SecureTokens.isWellFormed("")).isFalse();
		assertThat(SecureTokens.isWellFormed("short")).isFalse();
		assertThat(SecureTokens.isWellFormed("a".repeat(42) + "=")).isFalse();
		assertThat(SecureTokens.isWellFormed("' or 1=1 --" + "a".repeat(32))).isFalse();
	}

	@Test
	void sha256HexMatchesKnownVector() {
		assertThat(SecureTokens.sha256Hex("abc"))
				.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
	}

}
