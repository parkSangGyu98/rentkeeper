package com.rentkeeper.auth.rememberme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RememberMeKeyTest {

	@Test
	void prodRequiresKey() {
		assertThatThrownBy(() -> RememberMeKey.resolve("", false)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("APP_REMEMBER_ME_KEY");
		assertThatThrownBy(() -> RememberMeKey.resolve(null, false)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void rejectsShortOrUnresolvedKey() {
		assertThatThrownBy(() -> RememberMeKey.resolve("short-key", false)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> RememberMeKey.resolve("${APP_REMEMBER_ME_KEY}-padding-to-be-long-enough", true))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void localUsesTemporaryKeyWhenMissing() {
		String first = RememberMeKey.resolve("", true);
		assertThat(first).hasSizeGreaterThanOrEqualTo(RememberMeKey.MIN_LENGTH);
		assertThat(RememberMeKey.resolve("", true)).isNotEqualTo(first);
	}

	@Test
	void configuredKeyIsUsedAsIs() {
		String key = "k".repeat(44);
		assertThat(RememberMeKey.resolve(key, false)).isEqualTo(key);
	}

}
